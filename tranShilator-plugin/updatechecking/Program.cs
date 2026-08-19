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
                "用法: CheckUpdate.exe [--path 插件目录] [--force] [--no-pause]\n\n默认目录: %LOCALAPPDATA%\\PixivNovelTranslator\\tranShilator-plugin",
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

        ApplicationConfiguration.Initialize();
        Application.Run(new UpdateForm(options));
    }
}

internal sealed record UpdateOptions(string? Path, bool Force, bool NoPause)
{
    public static UpdateOptions Parse(string[] args)
    {
        string? path = null;
        var force = false;
        var noPause = false;
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
                case "--path" when i + 1 < args.Length:
                    path = args[++i];
                    break;
                default:
                    throw new ArgumentException($"未知参数: {args[i]}");
            }
        }
        return new UpdateOptions(path, force, noPause);
    }
}
