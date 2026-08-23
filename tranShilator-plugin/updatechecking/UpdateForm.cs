using System.Drawing;
using System.Windows.Forms;

namespace PixivNovelTranslatorUpdater;

internal sealed class UpdateForm : Form
{
    private readonly Label _statusLabel;
    private readonly Label _versionLabel;
    private readonly TextBox _pathBox;
    private readonly ProgressBar _progressBar;
    private readonly RichTextBox _logBox;
    private readonly Label _summaryLabel;
    private readonly Button _checkButton;
    private readonly Button _updateButton;
    private readonly Button _browseButton;
    private readonly Button _copyPathButton;
    private readonly TableLayoutPanel _layout;
    private readonly UpdateService _updateService = new();
    private UpdateOptions _options;
    private UpdateCheckResult? _lastCheck;
    private bool _running;
    private bool _started;
    private bool _settingPath;

    public UpdateForm(UpdateOptions options)
    {
        _options = options;
        Text = "CheckUpdate - Pixiv Novel Translator";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(820, 680);
        MinimumSize = new Size(820, 680);
        MaximumSize = new Size(820, 680);
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = Color.FromArgb(247, 248, 250);
        Font = new Font("Segoe UI", 9F);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;

        _layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(32, 24, 32, 22),
            ColumnCount = 1,
            RowCount = 10,
            BackColor = BackColor,
            GrowStyle = TableLayoutPanelGrowStyle.FixedSize
        };
        _layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
        AddRows();
        Controls.Add(_layout);

        var title = new Label
        {
            AutoSize = true,
            Text = "Pixiv Novel Translator",
            Font = new Font("Segoe UI Semibold", 18F, FontStyle.Bold),
            ForeColor = Color.FromArgb(32, 35, 42),
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0)
        };
        _layout.Controls.Add(title, 0, 0);

        var subtitle = new Label
        {
            AutoSize = true,
            Text = "CheckUpdate  ·  插件更新助手",
            Font = new Font("Segoe UI", 9F),
            ForeColor = Color.FromArgb(105, 111, 122),
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0)
        };
        _layout.Controls.Add(subtitle, 0, 1);

        var statusPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0),
            BackColor = BackColor
        };
        statusPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55F));
        statusPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45F));

        _statusLabel = new Label
        {
            AutoSize = false,
            Text = "准备检查更新",
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI Semibold", 11F, FontStyle.Bold),
            ForeColor = Color.FromArgb(55, 60, 70),
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };
        statusPanel.Controls.Add(_statusLabel, 0, 0);

        _versionLabel = new Label
        {
            AutoSize = false,
            Text = "当前版本 --  ·  远程版本 --",
            TextAlign = ContentAlignment.MiddleRight,
            Font = new Font("Segoe UI", 9F),
            ForeColor = Color.FromArgb(105, 111, 122),
            Dock = DockStyle.Fill,
            AutoEllipsis = true,
            Margin = new Padding(0)
        };
        statusPanel.Controls.Add(_versionLabel, 1, 0);
        _layout.Controls.Add(statusPanel, 0, 2);

        var pathCaption = new Label
        {
            AutoSize = true,
            Text = "更新目录",
            Font = new Font("Segoe UI Semibold", 9F, FontStyle.Bold),
            ForeColor = Color.FromArgb(75, 80, 90),
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.BottomLeft,
            Margin = new Padding(0, 0, 0, 2)
        };
        _layout.Controls.Add(pathCaption, 0, 3);

        var pathPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0),
            BackColor = BackColor
        };
        pathPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
        pathPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150F));

        _pathBox = new TextBox
        {
            Text = options.Path ?? string.Empty,
            PlaceholderText = "留空将自动定位插件目录",
            Font = new Font("Segoe UI", 9F),
            ForeColor = Color.FromArgb(55, 60, 70),
            BackColor = Color.White,
            Dock = DockStyle.Fill,
            Margin = new Padding(0, 0, 10, 0)
        };
        _pathBox.TextChanged += (_, _) =>
        {
            if (_settingPath) return;
            _options = _options with { Path = string.IsNullOrWhiteSpace(_pathBox.Text) ? null : _pathBox.Text.Trim() };
            _lastCheck = null;
            if (_updateButton is not null) SetUpdateButtonEnabled(false);
        };
        pathPanel.Controls.Add(_pathBox, 0, 0);

        _browseButton = MakeButton("选择目录", Color.FromArgb(235, 237, 241), Color.FromArgb(55, 60, 70));
        _browseButton.Dock = DockStyle.Fill;
        _browseButton.Margin = new Padding(0);
        _browseButton.Click += BrowseButton_Click;
        pathPanel.Controls.Add(_browseButton, 1, 0);
        _layout.Controls.Add(pathPanel, 0, 4);

        _progressBar = new ProgressBar
        {
            Style = ProgressBarStyle.Marquee,
            MarqueeAnimationSpeed = 24,
            Dock = DockStyle.Fill,
            Margin = new Padding(0, 5, 0, 5),
            Visible = false
        };
        _layout.Controls.Add(_progressBar, 0, 5);

        _summaryLabel = new Label
        {
            AutoSize = false,
            Text = "准备检查更新。",
            Font = new Font("Segoe UI", 10F),
            ForeColor = Color.FromArgb(55, 60, 70),
            BackColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
            Padding = new Padding(12, 6, 12, 6),
            Margin = new Padding(0)
        };
        _layout.Controls.Add(_summaryLabel, 0, 6);

        _logBox = new RichTextBox
        {
            ReadOnly = true,
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Color.FromArgb(38, 42, 50),
            ForeColor = Color.FromArgb(229, 233, 240),
            Font = new Font("Consolas", 8.5F),
            Dock = DockStyle.Fill,
            Padding = new Padding(8),
            DetectUrls = false,
            ScrollBars = RichTextBoxScrollBars.Vertical,
            Margin = new Padding(0),
            Visible = true
        };
        _layout.Controls.Add(_logBox, 0, 7);

        var actionPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 3,
            RowCount = 1,
            Margin = new Padding(0)
        };
        actionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 132F));
        actionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 132F));
        actionPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 156F));

        _checkButton = MakeButton("检查更新", Color.FromArgb(235, 237, 241), Color.FromArgb(55, 60, 70));
        _checkButton.Dock = DockStyle.Fill;
        _checkButton.Margin = new Padding(0, 0, 10, 0);
        _checkButton.Click += async (_, _) => await CheckForUpdatesAsync();
        actionPanel.Controls.Add(_checkButton, 0, 0);

        _updateButton = MakeButton("立即更新", Color.FromArgb(26, 115, 232), Color.White);
        _updateButton.Dock = DockStyle.Fill;
        _updateButton.Margin = new Padding(0, 0, 10, 0);
        _updateButton.Click += async (_, _) => await InstallUpdateAsync();
        actionPanel.Controls.Add(_updateButton, 1, 0);
        SetUpdateButtonEnabled(false);

        _copyPathButton = MakeButton("复制插件目录", Color.FromArgb(235, 237, 241), Color.FromArgb(55, 60, 70));
        _copyPathButton.Dock = DockStyle.Fill;
        _copyPathButton.Margin = new Padding(0);
        _copyPathButton.Click += CopyPathButton_Click;
        actionPanel.Controls.Add(_copyPathButton, 2, 0);
        _layout.Controls.Add(actionPanel, 0, 8);

        var footer = new Label
        {
            AutoSize = false,
            Text = "更新完成后，请在浏览器扩展管理页点击扩展卡片上的“重新加载”。",
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = Color.FromArgb(105, 111, 122),
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };
        _layout.Controls.Add(footer, 0, 9);

        Shown += async (_, _) =>
        {
            if (!_started)
            {
                _started = true;
                await CheckForUpdatesAsync();
            }
        };
    }

    private void AddRows()
    {
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 28F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 26F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 38F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 20F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 78F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 204F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
        _layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 48F));
    }

    private static Button MakeButton(string text, Color backColor, Color foreColor)
    {
        return new Button
        {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            FlatAppearance = { BorderSize = 0 },
            BackColor = backColor,
            ForeColor = foreColor,
            Font = new Font("Segoe UI Semibold", 9F, FontStyle.Bold),
            Cursor = Cursors.Hand,
            TabStop = false,
            UseVisualStyleBackColor = false
        };
    }

    private async Task CheckForUpdatesAsync()
    {
        if (_running) return;
        _running = true;
        SetBusy(true);
        _lastCheck = null;
        SetUpdateButtonEnabled(false);
        _statusLabel.Text = "正在检查更新...";
        _statusLabel.ForeColor = Color.FromArgb(55, 60, 70);
        _versionLabel.Text = "当前版本 --  ·  远程版本 --";
        _summaryLabel.Text = "正在连接更新服务器，请稍候...";
        _progressBar.Visible = true;
        _progressBar.Style = ProgressBarStyle.Marquee;
        _logBox.Clear();

        try
        {
            var result = await _updateService.CheckAsync(_options, AppendLog, SetProgress);
            _lastCheck = result;
            UpdateVersionLabel(result);
            _statusLabel.Text = result.Success
                ? result.UpdateAvailable ? "发现新版本" : "已是最新版本"
                : "检查失败";
            _statusLabel.ForeColor = result.Success
                ? result.UpdateAvailable ? Color.FromArgb(26, 115, 232) : Color.FromArgb(30, 126, 52)
                : Color.FromArgb(197, 34, 31);
            _summaryLabel.Text = result.Success
                ? result.UpdateAvailable
                    ? $"发现版本 {result.LatestVersion}，可以下载并安装。"
                    : $"当前安装版本 {result.CurrentVersion ?? "未知"} 已经是较新版本，无需安装。"
                : "无法完成检查，请确认网络连接后重试。";
            SetUpdateButtonEnabled(result.Success && result.UpdateAvailable);
        }
        finally
        {
            _running = false;
            SetBusy(false);
            _progressBar.Visible = false;
        }
    }

    private async Task InstallUpdateAsync()
    {
        if (_running || _lastCheck is null || !_lastCheck.Success || !_lastCheck.UpdateAvailable) return;
        _running = true;
        SetBusy(true);
        _statusLabel.Text = "正在更新...";
        _statusLabel.ForeColor = Color.FromArgb(55, 60, 70);
        _summaryLabel.Text = "正在下载并安装更新，请不要关闭窗口。";
        _progressBar.Visible = true;
        _progressBar.Style = ProgressBarStyle.Continuous;
        _progressBar.Value = 0;
        _logBox.AppendText("\r\n");

        try
        {
            var result = await _updateService.InstallAsync(_options, _lastCheck, AppendLog, SetProgress);
            _statusLabel.Text = result.Success ? "更新完成" : "更新失败";
            _statusLabel.ForeColor = result.Success
                ? Color.FromArgb(30, 126, 52)
                : Color.FromArgb(197, 34, 31);
            _summaryLabel.Text = result.Success
                ? "更新已安装，请到浏览器扩展管理页点击“重新加载”。"
                : "更新没有完成，请查看下面的详细日志。";
            SetUpdateButtonEnabled(false);
        }
        finally
        {
            _running = false;
            SetBusy(false);
            _progressBar.Visible = false;
        }
    }

    private void SetUpdateButtonEnabled(bool enabled)
    {
        _updateButton.Enabled = enabled;
        _updateButton.BackColor = enabled ? Color.FromArgb(26, 115, 232) : Color.FromArgb(225, 227, 231);
        _updateButton.ForeColor = enabled ? Color.White : Color.FromArgb(145, 149, 156);
    }

    private void SetBusy(bool busy)
    {
        _checkButton.Enabled = !busy;
        _browseButton.Enabled = !busy;
        _copyPathButton.Enabled = !busy;
        _pathBox.ReadOnly = busy;
    }

    private void SetProgress(int? value)
    {
        if (_progressBar.IsDisposed) return;
        if (_progressBar.InvokeRequired)
        {
            _progressBar.BeginInvoke(new Action<int?>(SetProgress), value);
            return;
        }

        if (value is null)
        {
            _progressBar.Style = ProgressBarStyle.Marquee;
            return;
        }

        _progressBar.Style = ProgressBarStyle.Continuous;
        _progressBar.Value = Math.Clamp(value.Value, 0, 100);
    }

    private void UpdateVersionLabel(UpdateCheckResult result)
    {
        var current = result.CurrentVersion ?? "未安装";
        var latest = result.LatestVersion ?? "--";
        _versionLabel.Text = $"当前版本 {current}  ·  远程版本 {latest}";
        if (!string.IsNullOrWhiteSpace(result.ExtensionDirectory))
        {
            _settingPath = true;
            try
            {
                _pathBox.Text = result.ExtensionDirectory;
            }
            finally
            {
                _settingPath = false;
            }
        }
    }

    private void BrowseButton_Click(object? sender, EventArgs e)
    {
        using var dialog = new FolderBrowserDialog
        {
            Description = "选择已加载的 tranShilator-plugin 插件目录",
            UseDescriptionForTitle = true,
            ShowNewFolderButton = false
        };
        if (dialog.ShowDialog(this) != DialogResult.OK) return;
        _pathBox.Text = dialog.SelectedPath;
    }

    private void CopyPathButton_Click(object? sender, EventArgs e)
    {
        try
        {
            var path = UpdateService.ResolveExtensionDirectory(_options.Path);
            Clipboard.SetText(path);
            _pathBox.Text = path;
            _statusLabel.Text = "插件目录已复制";
            _statusLabel.ForeColor = Color.FromArgb(30, 126, 52);
            _summaryLabel.Text = "插件目录已复制到剪贴板。";
        }
        catch (Exception error)
        {
            _statusLabel.Text = "复制失败";
            _statusLabel.ForeColor = Color.FromArgb(197, 34, 31);
            _summaryLabel.Text = "复制插件目录失败，请查看下面的详细日志。";
            AppendLog("\r\n复制插件目录失败: " + error.Message + "\r\n");
        }
    }

    private void AppendLog(string text)
    {
        if (_logBox.IsDisposed) return;
        if (_logBox.InvokeRequired)
        {
            _logBox.BeginInvoke(new Action<string>(AppendLog), text);
            return;
        }
        _logBox.AppendText(text.Replace("\r", string.Empty));
        _logBox.SelectionStart = _logBox.TextLength;
        _logBox.ScrollToCaret();
    }
}
