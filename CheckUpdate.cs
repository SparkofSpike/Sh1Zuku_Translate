using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PixivNovelTranslatorUpdater;

internal static class Program
{
    private const string Repository = "SparkofSpike/Sh1Zuku_Translate";
    private const string ApiUrl = "https://api.github.com/repos/" + Repository + "/releases/latest";
    private const string ReleasePage = "https://github.com/" + Repository + "/releases/latest";
    private const string ProductName = "Pixiv Novel Translator";

    private static async Task<int> Main(string[] args)
    {
        var options = Options.Parse(args);
        PrintHeader();

        try
        {
            var extensionDirectory = ResolveExtensionDirectory(options.Path);
            var currentVersion = ReadManifestVersion(extensionDirectory);
            Console.WriteLine($"安装目录: {extensionDirectory}");
            Console.WriteLine($"当前版本: {(currentVersion ?? "未安装")}");
            Console.WriteLine();

            using var client = CreateHttpClient();
            Console.WriteLine("[1/4] 正在检查最新版本...");
            var release = await GetLatestReleaseAsync(client);
            Console.WriteLine($"最新版本: {release.Version}");

            if (!options.Force && currentVersion is not null && CompareVersions(release.Version, currentVersion) <= 0)
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine("已经是最新版本，无需更新。");
                Console.ResetColor();
                OpenExtensionsPage(options.Browser);
                return Finish(options, 0);
            }

            var zipPath = Path.Combine(Path.GetTempPath(), $"pnt-update-{Guid.NewGuid():N}.zip");
            var stagingDirectory = Path.Combine(Path.GetTempPath(), $"pnt-update-{Guid.NewGuid():N}");
            try
            {
                Console.WriteLine();
                Console.WriteLine("[2/4] 正在下载更新包...");
                await DownloadAsync(client, release, zipPath);
                VerifyDigest(zipPath, release.Digest);

                Console.WriteLine();
                Console.WriteLine("[3/4] 正在校验并安装插件...");
                var extractedDirectory = ExtractExtension(zipPath, stagingDirectory, release.Version);
                InstallExtension(extractedDirectory, extensionDirectory);
            }
            finally
            {
                TryDeleteFile(zipPath);
                TryDeleteDirectory(stagingDirectory);
            }

            Console.WriteLine();
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("[4/4] 更新完成！");
            Console.ResetColor();
            Console.WriteLine($"插件位置: {extensionDirectory}");
            Console.WriteLine();
            Console.WriteLine("浏览器不会自动刷新加载解压缩的插件，接下来请在扩展管理页点击一次刷新按钮。");
            OpenExtensionsPage(options.Browser);
            return Finish(options, 0);
        }
        catch (Exception error)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine();
            Console.WriteLine("更新失败: " + error.Message);
            Console.ResetColor();
            Console.WriteLine();
            Console.WriteLine("如果插件正在使用，请先关闭 Pixiv 页面或浏览器后重试。");
            return Finish(options, 1);
        }
    }

    private static void PrintHeader()
    {
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("===============================================");
        Console.WriteLine("  CheckUpdate - Pixiv Novel Translator 更新器");
        Console.WriteLine("===============================================");
        Console.ResetColor();
        Console.WriteLine();
    }

    private static HttpClient CreateHttpClient()
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            UseProxy = true,
            Proxy = WebRequest.DefaultWebProxy
        };
        var client = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(60)
        };
        client.DefaultRequestHeaders.UserAgent.Add(new ProductInfoHeaderValue("CheckUpdate", "1.0"));
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        return client;
    }

    private static async Task<ReleaseInfo> GetLatestReleaseAsync(HttpClient client)
    {
        using var response = await client.GetAsync(ApiUrl, HttpCompletionOption.ResponseHeadersRead);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(
                $"无法访问 GitHub Release API（HTTP {(int)response.StatusCode}）。请检查网络，或确认仓库为公开状态。\n{ReleasePage}");
        }

        await using var stream = await response.Content.ReadAsStreamAsync();
        using var document = await JsonDocument.ParseAsync(stream);
        var root = document.RootElement;
        var tag = root.TryGetProperty("tag_name", out var tagElement) ? tagElement.GetString() : null;
        var version = NormalizeVersion(tag);
        if (version is null)
        {
            throw new InvalidOperationException("最新 Release 没有合法的版本号标签，例如 v1.2.0。");
        }

        var assets = new List<ReleaseAsset>();
        if (root.TryGetProperty("assets", out var assetsElement))
        {
            foreach (var asset in assetsElement.EnumerateArray())
            {
                var name = asset.TryGetProperty("name", out var nameElement) ? nameElement.GetString() : null;
                var url = asset.TryGetProperty("browser_download_url", out var urlElement)
                    ? urlElement.GetString()
                    : null;
                var digest = asset.TryGetProperty("digest", out var digestElement)
                    ? digestElement.GetString()
                    : null;
                if (!string.IsNullOrWhiteSpace(name) && !string.IsNullOrWhiteSpace(url))
                {
                    assets.Add(new ReleaseAsset(name, url, digest));
                }
            }
        }

        var zip = assets.FirstOrDefault(asset =>
            Regex.IsMatch(asset.Name, @"^pixiv-novel-translator-.*\.zip$", RegexOptions.IgnoreCase));
        zip ??= assets.FirstOrDefault(asset =>
            string.Equals(asset.Name, "pixiv-novel-translator.zip", StringComparison.OrdinalIgnoreCase));
        zip ??= new ReleaseAsset(
            "pixiv-novel-translator.zip",
            $"https://github.com/{Repository}/releases/latest/download/pixiv-novel-translator.zip",
            null);

        return new ReleaseInfo(version, zip);
    }

    private static async Task DownloadAsync(HttpClient client, ReleaseInfo release, string outputPath)
    {
        using var response = await client.GetAsync(release.Asset.Url, HttpCompletionOption.ResponseHeadersRead);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException($"下载更新包失败（HTTP {(int)response.StatusCode}）。");
        }

        var total = response.Content.Headers.ContentLength;
        await using var input = await response.Content.ReadAsStreamAsync();
        await using var output = File.Create(outputPath);
        var buffer = new byte[64 * 1024];
        long downloaded = 0;
        int read;
        while ((read = await input.ReadAsync(buffer)) > 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, read));
            downloaded += read;
            if (total is > 0)
            {
                Console.Write($"\r  {downloaded / 1024d / 1024d:0.0} / {total.Value / 1024d / 1024d:0.0} MB ({downloaded * 100d / total.Value:0}%)");
            }
            else
            {
                Console.Write($"\r  已下载 {downloaded / 1024d / 1024d:0.0} MB");
            }
        }
        Console.WriteLine();
    }

    private static void VerifyDigest(string zipPath, string? digest)
    {
        if (string.IsNullOrWhiteSpace(digest))
        {
            Console.WriteLine("  Release 未提供 SHA-256 摘要，将继续校验压缩包内容。");
            return;
        }

        var expected = digest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase)
            ? digest["sha256:".Length..]
            : digest;
        var actual = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(zipPath))).ToLowerInvariant();
        if (!string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("更新包 SHA-256 校验失败，已停止安装。");
        }
        Console.WriteLine("  SHA-256 校验通过。");
    }

    private static string ExtractExtension(string zipPath, string stagingDirectory, string expectedVersion)
    {
        Directory.CreateDirectory(stagingDirectory);
        using (var archive = ZipFile.OpenRead(zipPath))
        {
            var stagingRoot = Path.GetFullPath(stagingDirectory) + Path.DirectorySeparatorChar;
            foreach (var entry in archive.Entries)
            {
                var destination = Path.GetFullPath(Path.Combine(stagingDirectory, entry.FullName));
                if (!destination.StartsWith(stagingRoot, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidOperationException("更新包包含不安全的文件路径，已停止安装。");
                }
            }
        }
        ZipFile.ExtractToDirectory(zipPath, stagingDirectory, overwriteFiles: true);

        var direct = Path.Combine(stagingDirectory, "pixiv-novel-translator");
        var manifestPath = File.Exists(Path.Combine(direct, "manifest.json"))
            ? Path.Combine(direct, "manifest.json")
            : Directory.GetFiles(stagingDirectory, "manifest.json", SearchOption.AllDirectories).FirstOrDefault();
        if (manifestPath is null)
        {
            throw new InvalidOperationException("更新包中没有找到 manifest.json。");
        }

        var extensionDirectory = Path.GetDirectoryName(manifestPath)!;
        var version = ReadManifestVersion(extensionDirectory);
        if (version is null || CompareVersions(version, expectedVersion) != 0)
        {
            throw new InvalidOperationException(
                $"更新包版本校验失败：Release 是 {expectedVersion}，插件 manifest 是 {version ?? "未知"}。");
        }
        return extensionDirectory;
    }

    private static void InstallExtension(string sourceDirectory, string targetDirectory)
    {
        var parent = Path.GetDirectoryName(targetDirectory)!;
        Directory.CreateDirectory(parent);
        var backupDirectory = targetDirectory + ".backup-" + DateTime.Now.ToString("yyyyMMdd-HHmmss");

        if (Directory.Exists(targetDirectory))
        {
            Directory.Move(targetDirectory, backupDirectory);
        }

        try
        {
            CopyDirectory(sourceDirectory, targetDirectory);
        }
        catch
        {
            TryDeleteDirectory(targetDirectory);
            if (Directory.Exists(backupDirectory))
            {
                Directory.Move(backupDirectory, targetDirectory);
            }
            throw;
        }

        Console.WriteLine("  文件已安装。旧版本备份: " + backupDirectory);
    }

    private static void CopyDirectory(string source, string target)
    {
        Directory.CreateDirectory(target);
        foreach (var file in Directory.GetFiles(source))
        {
            File.Copy(file, Path.Combine(target, Path.GetFileName(file)), overwrite: true);
        }
        foreach (var directory in Directory.GetDirectories(source))
        {
            CopyDirectory(directory, Path.Combine(target, Path.GetFileName(directory)));
        }
    }

    private static string ResolveExtensionDirectory(string? requestedPath)
    {
        if (!string.IsNullOrWhiteSpace(requestedPath))
        {
            return Path.GetFullPath(Environment.ExpandEnvironmentVariables(requestedPath));
        }

        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var installed = Path.Combine(localAppData, "PixivNovelTranslator", "pixiv-novel-translator");
        if (File.Exists(Path.Combine(installed, "manifest.json"))) return installed;

        // Also support placing CheckUpdate.exe beside the unpacked extension.
        var besideUpdater = Path.Combine(AppContext.BaseDirectory, "pixiv-novel-translator");
        if (File.Exists(Path.Combine(besideUpdater, "manifest.json"))) return besideUpdater;

        return installed;
    }

    private static string? ReadManifestVersion(string extensionDirectory)
    {
        var path = Path.Combine(extensionDirectory, "manifest.json");
        if (!File.Exists(path)) return null;
        using var document = JsonDocument.Parse(File.ReadAllText(path));
        if (!document.RootElement.TryGetProperty("version", out var version)) return null;
        return NormalizeVersion(version.GetString());
    }

    private static string? NormalizeVersion(string? version)
    {
        if (string.IsNullOrWhiteSpace(version)) return null;
        var normalized = version.Trim().TrimStart('v', 'V').Split('+')[0].Split('-')[0];
        return Regex.IsMatch(normalized, @"^[0-9]+(?:\.[0-9]+)*$") ? normalized : null;
    }

    private static int CompareVersions(string left, string right)
    {
        var a = left.Split('.').Select(int.Parse).ToArray();
        var b = right.Split('.').Select(int.Parse).ToArray();
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }

    private static void OpenExtensionsPage(string browser)
    {
        var url = browser.Equals("chrome", StringComparison.OrdinalIgnoreCase)
            ? "chrome://extensions"
            : "edge://extensions";
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            Console.WriteLine($"已打开 {url}，请点击插件卡片上的刷新按钮。");
        }
        catch
        {
            Console.WriteLine($"请手动打开 {url}，然后点击插件卡片上的刷新按钮。");
        }
    }

    private static int Finish(Options options, int exitCode)
    {
        if (!options.NoPause)
        {
            Console.WriteLine();
            Console.WriteLine("按任意键退出...");
            try { Console.ReadKey(intercept: true); } catch { /* non-interactive terminal */ }
        }
        return exitCode;
    }

    private static void TryDeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void TryDeleteDirectory(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, recursive: true); } catch { }
    }

    private sealed record ReleaseInfo(string Version, ReleaseAsset Asset)
    {
        public string? Digest => Asset.Digest;
    }

    private sealed record ReleaseAsset(string Name, string Url, string? Digest);

    private sealed record Options(string Browser, string? Path, bool Force, bool NoPause)
    {
        public static Options Parse(string[] args)
        {
            var browser = "edge";
            string? path = null;
            var force = false;
            var noPause = false;
            for (var i = 0; i < args.Length; i++)
            {
                switch (args[i].ToLowerInvariant())
                {
                    case "--chrome":
                        browser = "chrome";
                        break;
                    case "--edge":
                        browser = "edge";
                        break;
                    case "--force":
                        force = true;
                        break;
                    case "--no-pause":
                        noPause = true;
                        break;
                    case "--path" when i + 1 < args.Length:
                        path = args[++i];
                        break;
                    case "--help":
                    case "-h":
                        PrintUsage();
                        Environment.Exit(0);
                        break;
                    default:
                        throw new ArgumentException($"未知参数: {args[i]}");
                }
            }
            return new Options(browser, path, force, noPause);
        }

        private static void PrintUsage()
        {
            Console.WriteLine("用法: CheckUpdate.exe [--chrome|--edge] [--path 插件目录] [--force] [--no-pause]");
            Console.WriteLine("默认目录: %LOCALAPPDATA%\\PixivNovelTranslator\\pixiv-novel-translator");
        }
    }
}
