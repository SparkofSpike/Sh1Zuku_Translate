using System.Diagnostics;
using System.Windows.Forms;

namespace PixivNovelTranslatorUpdater;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Any(arg => arg.Equals("--help", StringComparison.OrdinalIgnoreCase)
            || arg.Equals("-h", StringComparison.OrdinalIgnoreCase)))
        {
            MessageBox.Show(
                "用法: CheckUpdate.exe [--path 插件目录] [--force] [--no-pause]\n\n"
                + "默认目录: %LOCALAPPDATA%\\PixivNovelTranslator\\tranShilator-plugin\n"
                + "若 CheckUpdate.exe 与 manifest.json 同目录，则默认更新该目录。",
                "CheckUpdate",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        UpdateOptions options;
        try
        {
            options = UpdateOptions.Parse(args);
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "CheckUpdate", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return;
        }

        // 自举。release 包的结构是 tranShilator-plugin/{manifest.json, CheckUpdate.exe}，
        // 于是从解压目录双击运行时，ResolveExtensionDirectory 会把"自己脚下"当成插件
        // 目录；而 InstallExtension 要 Directory.Move 这个目录 —— 移动一个包含正在运行
        // 的自己的目录会被 Windows 直接拒绝，用户看到「文件正在使用」，且关浏览器、
        // 重启电脑都没用（占用者就是它自己）。
        // 处理：先把自己复制到 %TEMP%，再用副本重启，并把原目录作为 --path 传下去。
        if (RelocateSelfIfInsideExtension(options))
        {
            return;
        }

        // 副本启动后要给原进程一点时间退出并释放对原目录的句柄，否则紧接着的
        // Directory.Move 仍可能失败。单文件自解压本身不慢，这里保守多等一会儿。
        if (options.Relocated)
        {
            Thread.Sleep(600);
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new UpdateForm(options));
    }

    /// <summary>
    /// 当更新器自己就躺在插件目录里（与 manifest.json 同级）时，把自己搬到 %TEMP%
    /// 并以副本重启。返回 true 表示「已交棒给副本，本进程应立即退出」。
    /// </summary>
    private static bool RelocateSelfIfInsideExtension(UpdateOptions options)
    {
        if (options.Relocated) return false; // 已经是副本，避免无限自举

        var self = Environment.ProcessPath;
        if (string.IsNullOrEmpty(self)) return false;

        var currentDirectory = Path.GetDirectoryName(self);
        if (string.IsNullOrEmpty(currentDirectory)) return false;

        // 只有脚边就有 manifest.json 才说明这里是插件目录；否则维持原有语义。
        if (!File.Exists(Path.Combine(currentDirectory, "manifest.json"))) return false;

        // 只靠 --relocated 防递归：它是显式传递的，比“路径落在 %TEMP% 下”这种
        // 猜测可靠。用路径判断会误伤——万一插件目录本身就在临时目录下，
        // 自举就永远不会发生。

        var arguments = new List<string> { "--relocated" };
        // 用户没显式给 --path 时，把「自己脚下」这个目录原样接管过来，
        // 保证更新目标和双击之前完全一致。
        if (options.Path is null)
        {
            arguments.Add("--path");
            arguments.Add(currentDirectory);
        }
        if (options.Force) arguments.Add("--force");
        if (options.NoPause) arguments.Add("--no-pause");

        try
        {
            var relocatedDirectory = Path.Combine(Path.GetTempPath(), "pnt-updater-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(relocatedDirectory);
            var relocated = Path.Combine(relocatedDirectory, Path.GetFileName(self));
            File.Copy(self, relocated, overwrite: true);

            var startInfo = new ProcessStartInfo(relocated) { UseShellExecute = false };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }
            Process.Start(startInfo);
            return true;
        }
        catch
        {
            // 搬迁失败就在原地照旧跑：退回原来的行为，而不是什么都不做。
            return false;
        }
    }
}

internal sealed record UpdateOptions(string? Path, bool Force, bool NoPause, bool Relocated)
{
    public static UpdateOptions Parse(string[] args)
    {
        string? path = null;
        var force = false;
        var noPause = false;
        var relocated = false;
        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i].ToLowerInvariant())
            {
                case "--force":
                    force = true;
                    break;
                case "--no-pause":
                    noPause = true;
                    break;
                case "--relocated":
                    // 由 RelocateSelfIfInsideExtension 启动的副本会带上它。
                    relocated = true;
                    break;
                case "--path" when i + 1 < args.Length:
                    path = args[++i];
                    break;
                default:
                    throw new ArgumentException($"未知参数: {args[i]}");
            }
        }
        return new UpdateOptions(path, force, noPause, relocated);
    }
}
