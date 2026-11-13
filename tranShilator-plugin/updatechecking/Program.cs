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
        // 更新目标必须由父进程算好再传下去。不传的话，副本按自己的落点
        // （%TEMP%\pnt-updater-<guid>，旁边没有 manifest.json）去解析，一路落到
        // %LOCALAPPDATA% 的默认目录，而用户用 --path 指定的目录自始至终没被碰过，
        // 界面上还会显示「更新完成」。
        // 也必须在父进程里绝对化：副本的工作目录已经搬到 %TEMP%，相对路径会指错地方。
        arguments.Add("--path");
        arguments.Add(options.Path is null
            ? currentDirectory
            : Path.TrimEndingDirectorySeparator(
                Path.GetFullPath(Environment.ExpandEnvironmentVariables(options.Path))));
        if (options.Force) arguments.Add("--force");
        if (options.NoPause) arguments.Add("--no-pause");

        try
        {
            // 自举在 %TEMP% 留下一份约 150MB 的副本，而正在运行的进程删不掉自己
            // 所在的目录，所以只能等下一次运行时回收。更新的频率远低于一天一次，
            // 用一天的阈值就不会碰到另一个正在运行的实例。
            PruneStaleRelocatedCopies(TimeSpan.FromDays(1));

            var relocatedDirectory = Path.Combine(Path.GetTempPath(), "pnt-updater-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(relocatedDirectory);
            var relocated = Path.Combine(relocatedDirectory, Path.GetFileName(self));
            File.Copy(self, relocated, overwrite: true);

            // 关键：不设 WorkingDirectory 时，子进程会继承父进程的当前目录，也就是插件目录。
            // Windows 会为每个进程的当前目录保持一个不共享删除权限的句柄，于是副本虽然跑在
            // %TEMP%，更新时对插件目录做 Directory.Move 依旧报「另一个进程正在使用此文件」——
            // 和没有搬迁时一模一样。工作目录必须跟着一起搬到临时目录。
            var startInfo = new ProcessStartInfo(relocated)
            {
                UseShellExecute = false,
                WorkingDirectory = relocatedDirectory
            };
            foreach (var argument in arguments)
            {
                startInfo.ArgumentList.Add(argument);
            }
            Process.Start(startInfo);
            return true;
        }
        catch (Exception error)
        {
            // 退回原地运行时，只要更新目标就是当前目录，更新必定以「文件被占用」失败。
            // 这条以前是静默吞掉的，用户只会看到一条误导性的占用提示，所以必须说出来。
            MessageBox.Show(
                "无法把更新器临时复制到系统临时目录，将继续在当前位置运行。\n"
                + "如果更新目录正是当前目录，更新会失败。\n\n"
                + error.Message,
                "CheckUpdate",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            return false;
        }
    }

    /// <summary>
    /// 清掉早于 <paramref name="minimumAge"/> 的自举残留目录。
    /// 全部 best-effort：删不掉（另一个实例在用、权限不足）就留着，不影响本次自举。
    /// </summary>
    private static void PruneStaleRelocatedCopies(TimeSpan minimumAge)
    {
        const string Prefix = "pnt-updater-";

        string[] candidates;
        try
        {
            candidates = Directory.GetDirectories(Path.GetTempPath(), Prefix + "*");
        }
        catch
        {
            return;
        }

        var cutoff = DateTime.UtcNow - minimumAge;
        foreach (var directory in candidates)
        {
            try
            {
                // 目录名必须是自举生成的 GUID（"N" 格式，32 位），别人的同名目录不碰。
                var name = Path.GetFileName(directory);
                if (name.Length != Prefix.Length + 32) continue;
                if (Directory.GetCreationTimeUtc(directory) > cutoff) continue;
                Directory.Delete(directory, recursive: true);
            }
            catch
            {
                // 删不掉就跳过。
            }
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
