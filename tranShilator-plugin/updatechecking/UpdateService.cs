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
            // 只有真的是文件占用类问题才让人去关浏览器。这句以前是无条件打印的，
            // 于是任何失败（网络、磁盘、权限）都被误报成「插件正在使用」，
            // 用户关浏览器、重启都无济于事。
            //
            // 注意 HttpIOException 是 IOException 的子类：下载中途断线抛的就是它，
            // 所以必须先把它挑出来，否则网络问题还是会命中下面那条「去关浏览器」。
            if (error is HttpIOException)
            {
                log("下载过程中连接中断，请检查网络后重试。\n");
            }
            else if (error is IOException || error is UnauthorizedAccessException)
            {
                log("看起来是文件被占用或没有写入权限。请确认浏览器已完全退出"
                    + "（任务管理器里不应有 msedge.exe），并确认对插件目录有写权限。\n");
            }
            else
            {
                log($"错误类型: {error.GetType().Name}（不是文件占用类问题）\n");
            }
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
            // 必须去掉末尾的分隔符。带末尾反斜杠的路径会让 InstallExtension 拼出
            // “ext/.staging-<guid>”，也就是把临时目录建在插件目录内部，随后
            // Directory.Move 直接报「参数错误」，更新永远装不上。
            // PowerShell 的 Tab 补全会自动带上末尾反斜杠，所以这很容易碰到。
            return TrimTrailingSeparator(
                Path.GetFullPath(Environment.ExpandEnvironmentVariables(requestedPath)));
        }

        var updaterDirectory = Path.GetFullPath(AppContext.BaseDirectory);
        if (File.Exists(Path.Combine(updaterDirectory, "manifest.json")))
        {
            return TrimTrailingSeparator(updaterDirectory);
        }

        var localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        var installed = Path.Combine(localAppData, "PixivNovelTranslator", "tranShilator-plugin");
        if (File.Exists(Path.Combine(installed, "manifest.json"))) return installed;

        var legacy = Path.Combine(localAppData, "PixivNovelTranslator", "pixiv-novel-translator");
        if (File.Exists(Path.Combine(legacy, "manifest.json")))
        {
            // 只解析路径，不在这里迁移目录。这个方法「检查更新」也会调用，
            // 在检查阶段改文件系统会让用户的扩展在没点更新时就不见了：
            // 未打包扩展是按路径加载的，目录一改名浏览器就找不到它。
            // 原位更新即可，新旧布局对 InstallExtension 没有区别。
            return TrimTrailingSeparator(legacy);
        }

        return TrimTrailingSeparator(installed);
    }

    /// <summary>
    /// 去掉末尾目录分隔符，但保留根目录本身。
    /// </summary>
    private static string TrimTrailingSeparator(string path) =>
        Path.TrimEndingDirectorySeparator(path);

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

    /// <summary>
    /// 把 API 失败转成用户能行动的说明。403/429 最常见的原因不是网络、也不是
    /// 仓库私有，而是匿名调用被打满了配额（未认证每小时 60 次/IP）：用户看到
    /// 「确认仓库为公开状态」时无从下手，只能一遍遍重试。
    /// </summary>
    private static string DescribeApiFailure(HttpResponseMessage response)
    {
        var status = (int)response.StatusCode;
        var remaining = response.Headers.TryGetValues("X-RateLimit-Remaining", out var remainingValues)
            ? remainingValues.FirstOrDefault()?.Trim()
            : null;

        if ((status == 403 || status == 429) && remaining == "0")
        {
            var minutes = string.Empty;
            if (response.Headers.TryGetValues("X-RateLimit-Reset", out var resetValues)
                && long.TryParse(resetValues.FirstOrDefault(), out var epoch))
            {
                var wait = DateTimeOffset.FromUnixTimeSeconds(epoch) - DateTimeOffset.Now;
                minutes = $"约 {Math.Max(1, (int)Math.Ceiling(wait.TotalMinutes))} 分钟后";
            }
            return $"GitHub API 的匿名额度（每小时 60 次）已用完，请{minutes}重试。\n{ReleasePage}";
        }

        return $"无法访问 GitHub Release API（HTTP {status}）。请检查网络，或确认仓库为公开状态。\n{ReleasePage}";
    }

    private static async Task<ReleaseInfo> GetLatestReleaseAsync(HttpClient client)
    {
        using var response = await client.GetAsync(ApiUrl, HttpCompletionOption.ResponseHeadersRead);
        if (!response.IsSuccessStatusCode)
        {
            throw new InvalidOperationException(DescribeApiFailure(response));
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
        // 更新包可以有几十 MB，没必要整份读进内存再算哈希。
        using var stream = File.OpenRead(zipPath);
        var actual = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
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
        // 再兼一道：这里拿到的可能是调用方直接传进来的路径。
        targetDirectory = Path.TrimEndingDirectorySeparator(targetDirectory);
        var parent = Path.GetDirectoryName(targetDirectory)!;
        Directory.CreateDirectory(parent);

        var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
        var backupDirectory = $"{targetDirectory}.backup-{stamp}";
        // 同一秒内跑两次更新时别撞名，否则第一次 rename 会因为目标已存在而失败。
        if (Directory.Exists(backupDirectory))
        {
            backupDirectory = $"{targetDirectory}.backup-{stamp}-{Guid.NewGuid():N}";
        }

        // 新版本先完整复制到同一卷的同级目录，最后只靠两次 rename 换位。
        // 跨卷的 Directory.Move 会退化成复制，那就又回到「失败时留下半截目录」的老问题。
        var stagingDirectory = $"{targetDirectory}.staging-{Guid.NewGuid():N}";

        // 当前进程的镜像就是一个可用的更新器。包里没有 CheckUpdate.exe 时用它回填，
        // 否则更新完插件目录里就没有更新器了（下次更新便没有入口）。
        // 以前这里额外要求「更新器自己就在目标目录内」，但更新器现在总是先把自己
        // 复制到 %TEMP% 再重启，那个条件恒为 false —— 恰好把要防的情况全部放过。
        // 正在运行的镜像允许被读复制，从哪里跑都能用。
        var runningUpdater = Environment.ProcessPath;
        if (runningUpdater is not null && !File.Exists(runningUpdater))
        {
            runningUpdater = null;
        }

        var hadPreviousVersion = Directory.Exists(targetDirectory);

        try
        {
            // 第一阶段：只往 staging 里写。这里出任何错，用户正在用的扩展目录全程
            // 没被碰过 —— 不需要回滚，也不可能出现半写状态。异常原样上抛，
            // 调用方拿到的就是真实原因。
            try
            {
                CopyDirectory(sourceDirectory, stagingDirectory);
                var packagedUpdater = Path.Combine(sourceDirectory, "CheckUpdate.exe");
                var stagedUpdater = Path.Combine(stagingDirectory, "CheckUpdate.exe");
                if (!File.Exists(packagedUpdater))
                {
                    if (runningUpdater is not null)
                    {
                        File.Copy(runningUpdater, stagedUpdater, overwrite: true);
                        log("  发布包不含 CheckUpdate.exe，已用当前更新器补齐。\n");
                    }
                    else
                    {
                        // 无法自愈时至少要说出来：更新后这个目录里将没有更新器。
                        log("  发布包不含 CheckUpdate.exe，且无法读取当前更新器，"
                            + "更新后将没有可用的更新器。\n");
                    }
                }
            }
            catch
            {
                TryDeleteDirectory(stagingDirectory);
                throw;
            }

            // 第二阶段：两次 rename 换位。第一次失败时源目录原样保留，是干净的 no-op。
            if (hadPreviousVersion) Directory.Move(targetDirectory, backupDirectory);

            try
            {
                Directory.Move(stagingDirectory, targetDirectory);
            }
            catch (Exception swapError)
            {
                // 新版本没能就位，旧版本必须回去。
                if (hadPreviousVersion)
                {
                    try
                    {
                        Directory.Move(backupDirectory, targetDirectory);
                    }
                    catch (Exception rollbackError)
                    {
                        // 回滚也失败：把两条异常都带上。以前这里吞掉回滚异常，
                        // 用户只会看到「目标已存在」，真实原因（复制失败）反而被盖住。
                        throw new AggregateException(
                            "新版本安装失败，且旧版本回滚也失败。扩展目录可能不完整；"
                            + "旧版本完整保存在备份目录中，可手动改名恢复。",
                            swapError,
                            rollbackError);
                    }
                }
                throw;
            }
        }
        finally
        {
            // 换位成功时 staging 已不存在，这里是 no-op；任何提前失败都要把残留清掉。
            TryDeleteDirectory(stagingDirectory);
        }

        log(hadPreviousVersion
            ? $"  文件已安装。旧版本备份: {backupDirectory}\n"
            : "  文件已安装。\n");
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
        var a = ParseVersionParts(left);
        var b = ParseVersionParts(right);
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }

    /// <summary>
    /// 解析版本号里的数字段。NormalizeVersion 只保证「数字和点」，不限位数；
    /// 某一段超出 int（手写的 tag 就可能）时 int.Parse 会抛 OverflowException，
    /// 把一次正常的更新检查变成「检查失败」。这里饱和到 int.MaxValue：
    /// 超长版本号在语义上就是「很大」，比较结果仍然单调。
    /// </summary>
    private static int[] ParseVersionParts(string version) =>
        version.Split('.')
            .Select(part => int.TryParse(part, out var value) ? value : int.MaxValue)
            .ToArray();

    private static void TryDeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (!Directory.Exists(path)) return;
            Directory.Delete(path, recursive: true);
            return;
        }
        catch
        {
            // File.Copy 会把只读属性一起带过来，递归删除遇到只读文件会直接失败。
            // 清掉属性再试一次；仍然删不掉就放弃 —— 这里本来就在失败路径上，
            // 不能再抛异常把原始错误盖掉。
        }

        try
        {
            if (!Directory.Exists(path)) return;
            foreach (var file in Directory.GetFiles(path, "*", SearchOption.AllDirectories))
            {
                try { File.SetAttributes(file, FileAttributes.Normal); } catch { }
            }
            Directory.Delete(path, recursive: true);
        }
        catch { }
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
