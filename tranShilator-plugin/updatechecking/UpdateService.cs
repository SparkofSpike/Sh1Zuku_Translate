using System.IO.Compression;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PixivNovelTranslatorUpdater;

internal sealed class UpdateService
{
    private const string Repository = "SparkofSpike/Sh1Zuku_Translate";
    private const string ApiUrl = "https://api.github.com/repos/" + Repository + "/releases/latest";
    private const string ReleasePage = "https://github.com/" + Repository + "/releases/latest";

    public async Task<UpdateCheckResult> CheckAsync(
        UpdateOptions options,
        Action<string> log,
        Action<int?> progress)
    {
        string? extensionDirectory = null;
        string? currentVersion = null;
        try
        {
            progress(null);
            extensionDirectory = ResolveExtensionDirectory(options.Path);
            currentVersion = ReadManifestVersion(extensionDirectory);
            log($"安装目录: {extensionDirectory}\n");
            log($"当前版本: {(currentVersion ?? "未安装")}\n\n");

            using var client = CreateHttpClient();
            log("正在获取最新版本...\n");
            var release = await GetLatestReleaseAsync(client);
            var updateAvailable = options.Force
                || currentVersion is null
                || CompareVersions(release.Version, currentVersion) > 0;

            log($"最新版本: {release.Version}\n");
            if (updateAvailable)
            {
                log("发现可用更新，点击“立即更新”开始安装。\n");
            }
            else
            {
                log("已经是最新版本，无需更新。\n");
            }

            progress(100);
            return new UpdateCheckResult(
                true,
                extensionDirectory,
                currentVersion,
                release.Version,
                release.Asset.Url,
                release.Asset.Digest,
                updateAvailable,
                null);
        }
        catch (Exception error)
        {
            progress(0);
            log($"\n检查更新失败: {error.Message}\n\n");
            return new UpdateCheckResult(
                false,
                extensionDirectory,
                currentVersion,
                null,
                null,
                null,
                false,
                error.Message);
        }
    }

    public async Task<UpdateResult> InstallAsync(
        UpdateOptions options,
        UpdateCheckResult check,
        Action<string> log,
        Action<int?> progress)
    {
        if (!check.Success || !check.UpdateAvailable || check.LatestVersion is null || check.AssetUrl is null)
        {
            return new UpdateResult(true, check.ExtensionDirectory, check.CurrentVersion, check.LatestVersion, null);
        }

        var extensionDirectory = check.ExtensionDirectory ?? ResolveExtensionDirectory(options.Path);
        var zipPath = Path.Combine(Path.GetTempPath(), $"pnt-update-{Guid.NewGuid():N}.zip");
        var stagingDirectory = Path.Combine(Path.GetTempPath(), $"pnt-update-{Guid.NewGuid():N}");
        try
        {
            using var client = CreateHttpClient();
            progress(0);
            log("正在下载更新包...\n");
            await DownloadAsync(
                client,
                new ReleaseInfo(check.LatestVersion, new ReleaseAsset("update.zip", check.AssetUrl, check.Digest)),
                zipPath,
                log,
                progress);
            VerifyDigest(zipPath, check.Digest, log);

            progress(null);
            log("\n正在校验并安装插件...\n");
            var extractedDirectory = ExtractExtension(zipPath, stagingDirectory, check.LatestVersion);
            InstallExtension(extractedDirectory, extensionDirectory, log);
            progress(100);

            log("\n更新完成！\n");
            log($"插件位置: {extensionDirectory}\n");
            log("请在浏览器扩展管理页重新加载插件。\n");
            return new UpdateResult(true, extensionDirectory, check.CurrentVersion, check.LatestVersion, null);
        }
        catch (Exception error)
        {
            progress(0);
            log($"\n更新失败: {error.Message}\n\n");
            log("如果插件正在使用，请先关闭 Pixiv 页面或浏览器后重试。\n");
            return new UpdateResult(false, extensionDirectory, check.CurrentVersion, check.LatestVersion, error.Message);
        }
        finally
        {
            TryDeleteFile(zipPath);
            TryDeleteDirectory(stagingDirectory);
        }
    }

    public static string ResolveExtensionDirectory(string? requestedPath)
    {
        if (!string.IsNullOrWhiteSpace(requestedPath))
        {
            return Path.GetFullPath(Environment.ExpandEnvironmentVariables(requestedPath));
        }

        var updaterDirectory = Path.GetFullPath(AppContext.BaseDirectory);
        if (File.Exists(Path.Combine(updaterDirectory, "manifest.json")))
        {
            return updaterDirectory;
        }

        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var installed = Path.Combine(localAppData, "PixivNovelTranslator", "tranShilator-plugin");
        if (File.Exists(Path.Combine(installed, "manifest.json"))) return installed;

        var legacy = Path.Combine(localAppData, "PixivNovelTranslator", "pixiv-novel-translator");
        if (File.Exists(Path.Combine(legacy, "manifest.json")))
        {
            try
            {
                Directory.Move(legacy, installed);
                return installed;
            }
            catch
            {
                return legacy;
            }
        }

        return installed;
    }

    private static HttpClient CreateHttpClient()
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.All,
            UseProxy = true,
            Proxy = WebRequest.DefaultWebProxy
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(60) };
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
        var version = NormalizeVersion(tag)
            ?? throw new InvalidOperationException("最新 Release 没有合法的版本号标签，例如 v1.2.0。");

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
            Regex.IsMatch(asset.Name, @"^tranShilator-plugin-.*\.zip$", RegexOptions.IgnoreCase));
        zip ??= assets.FirstOrDefault(asset =>
            Regex.IsMatch(asset.Name, @"^pixiv-novel-translator-.*\.zip$", RegexOptions.IgnoreCase));
        zip ??= assets.FirstOrDefault(asset =>
            string.Equals(asset.Name, "tranShilator-plugin.zip", StringComparison.OrdinalIgnoreCase));
        zip ??= assets.FirstOrDefault(asset =>
            string.Equals(asset.Name, "pixiv-novel-translator.zip", StringComparison.OrdinalIgnoreCase));
        zip ??= new ReleaseAsset(
            "tranShilator-plugin.zip",
            $"https://github.com/{Repository}/releases/latest/download/tranShilator-plugin.zip",
            null);

        return new ReleaseInfo(version, zip);
    }

    private static async Task DownloadAsync(
        HttpClient client,
        ReleaseInfo release,
        string outputPath,
        Action<string> log,
        Action<int?> progress)
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
        var lastLoggedPercent = -1;
        int read;
        while ((read = await input.ReadAsync(buffer)) > 0)
        {
            await output.WriteAsync(buffer.AsMemory(0, read));
            downloaded += read;
            if (total is > 0)
            {
                var percent = (int)Math.Min(100, downloaded * 100d / total.Value);
                progress(percent);
                if (percent >= lastLoggedPercent + 5 || percent == 100)
                {
                    lastLoggedPercent = percent;
                    log($"  下载进度 {percent}%\n");
                }
            }
            else
            {
                progress(null);
                if (downloaded / (1024 * 1024) > lastLoggedPercent)
                {
                    lastLoggedPercent = (int)(downloaded / (1024 * 1024));
                    log($"  已下载 {lastLoggedPercent} MB\n");
                }
            }
        }
    }

    private static void VerifyDigest(string zipPath, string? digest, Action<string> log)
    {
        if (string.IsNullOrWhiteSpace(digest))
        {
            log("  Release 未提供 SHA-256 摘要，将继续校验压缩包内容。\n");
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
        log("  SHA-256 校验通过。\n");
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

        var direct = Path.Combine(stagingDirectory, "tranShilator-plugin");
        var legacyDirect = Path.Combine(stagingDirectory, "pixiv-novel-translator");
        var manifestPath = File.Exists(Path.Combine(direct, "manifest.json"))
            ? Path.Combine(direct, "manifest.json")
            : File.Exists(Path.Combine(legacyDirect, "manifest.json"))
                ? Path.Combine(legacyDirect, "manifest.json")
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

    private static void InstallExtension(string sourceDirectory, string targetDirectory, Action<string> log)
    {
        var parent = Path.GetDirectoryName(targetDirectory)!;
        Directory.CreateDirectory(parent);
        var backupDirectory = targetDirectory + ".backup-" + DateTime.Now.ToString("yyyyMMdd-HHmmss");
        string? updaterBackup = null;
        var runningUpdater = Environment.ProcessPath;
        var updaterInTarget = runningUpdater is not null
            && File.Exists(runningUpdater)
            && IsPathInside(runningUpdater, targetDirectory);

        if (updaterInTarget)
        {
            updaterBackup = Path.Combine(Path.GetTempPath(), $"pnt-updater-{Guid.NewGuid():N}.exe");
            File.Copy(runningUpdater!, updaterBackup, overwrite: true);
        }

        try
        {
            if (Directory.Exists(targetDirectory)) Directory.Move(targetDirectory, backupDirectory);
            try
            {
                CopyDirectory(sourceDirectory, targetDirectory);
                var packagedUpdater = Path.Combine(sourceDirectory, "CheckUpdate.exe");
                var installedUpdater = Path.Combine(targetDirectory, "CheckUpdate.exe");
                if (!File.Exists(packagedUpdater) && updaterBackup is not null)
                {
                    File.Copy(updaterBackup, installedUpdater, overwrite: true);
                }
            }
            catch
            {
                TryDeleteDirectory(targetDirectory);
                if (Directory.Exists(backupDirectory)) Directory.Move(backupDirectory, targetDirectory);
                throw;
            }
        }
        finally
        {
            if (updaterBackup is not null) TryDeleteFile(updaterBackup);
        }

        log($"  文件已安装。旧版本备份: {backupDirectory}\n");
    }

    private static bool IsPathInside(string filePath, string directory)
    {
        var file = Path.GetFullPath(filePath);
        var root = Path.GetFullPath(directory).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        return file.StartsWith(root, StringComparison.OrdinalIgnoreCase);
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

    private static void TryDeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void TryDeleteDirectory(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, recursive: true); } catch { }
    }

    private sealed record ReleaseInfo(string Version, ReleaseAsset Asset);

    private sealed record ReleaseAsset(string Name, string Url, string? Digest);
}

internal sealed record UpdateCheckResult(
    bool Success,
    string? ExtensionDirectory,
    string? CurrentVersion,
    string? LatestVersion,
    string? AssetUrl,
    string? Digest,
    bool UpdateAvailable,
    string? Error);

internal sealed record UpdateResult(
    bool Success,
    string? ExtensionDirectory,
    string? CurrentVersion,
    string? LatestVersion,
    string? Error);
