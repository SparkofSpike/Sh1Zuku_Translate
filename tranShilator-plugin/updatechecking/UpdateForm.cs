using System.Drawing;
using System.Text;
using System.Windows.Forms;

namespace PixivNovelTranslatorUpdater;

internal sealed class UpdateForm : Form
{
    private readonly Label _statusLabel;
    private readonly Label _pathLabel;
    private readonly ProgressBar _progressBar;
    private readonly RichTextBox _logBox;
    private readonly Button _updateButton;
    private readonly Button _browseButton;
    private readonly Button _copyPathButton;
    private readonly UpdateService _updateService = new();
    private UpdateOptions _options;
    private bool _running;
    private bool _started;

    public UpdateForm(UpdateOptions options)
    {
        _options = options;
        Text = "CheckUpdate - Pixiv Novel Translator";
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(760, 660);
        MinimumSize = new Size(760, 660);
        MaximumSize = new Size(760, 660);
        BackColor = Color.FromArgb(247, 248, 250);
        Font = new Font("Segoe UI", 9F);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;

        var title = new Label
        {
            AutoSize = true,
            Text = "Pixiv Novel Translator",
            Font = new Font("Segoe UI Semibold", 20F, FontStyle.Bold),
            ForeColor = Color.FromArgb(32, 35, 42),
            Location = new Point(36, 24)
        };
        Controls.Add(title);

        var subtitle = new Label
        {
            AutoSize = true,
            Text = "CheckUpdate · 插件更新助手",
            Font = new Font("Segoe UI", 10F),
            ForeColor = Color.FromArgb(105, 111, 122),
            Location = new Point(39, 64)
        };
        Controls.Add(subtitle);

        _statusLabel = new Label
        {
            AutoSize = false,
            Text = "准备检查更新",
            TextAlign = ContentAlignment.MiddleLeft,
            Font = new Font("Segoe UI Semibold", 11F, FontStyle.Bold),
            ForeColor = Color.FromArgb(26, 115, 232),
            Location = new Point(39, 108),
            Size = new Size(680, 30)
        };
        Controls.Add(_statusLabel);

        var pathCaption = new Label
        {
            AutoSize = true,
            Text = "更新目录",
            Font = new Font("Segoe UI Semibold", 9F, FontStyle.Bold),
            ForeColor = Color.FromArgb(75, 80, 90),
            Location = new Point(39, 153)
        };
        Controls.Add(pathCaption);

        _pathLabel = new Label
        {
            AutoEllipsis = true,
            BorderStyle = BorderStyle.FixedSingle,
            Text = options.Path ?? "自动定位（更新器所在插件目录优先）",
            ForeColor = Color.FromArgb(80, 85, 95),
            BackColor = Color.White,
            Location = new Point(39, 176),
            Size = new Size(580, 36),
            Padding = new Padding(9, 8, 9, 6)
        };
        Controls.Add(_pathLabel);

        _browseButton = MakeButton("选择目录", Color.FromArgb(235, 237, 241), Color.FromArgb(55, 60, 70));
        _browseButton.Location = new Point(631, 176);
        _browseButton.Size = new Size(88, 36);
        _browseButton.Click += BrowseButton_Click;
        Controls.Add(_browseButton);

        _progressBar = new ProgressBar
        {
            Style = ProgressBarStyle.Marquee,
            MarqueeAnimationSpeed = 24,
            Location = new Point(39, 245),
            Size = new Size(680, 10)
        };
        Controls.Add(_progressBar);

        _logBox = new RichTextBox
        {
            ReadOnly = true,
            BorderStyle = BorderStyle.None,
            BackColor = Color.FromArgb(38, 42, 50),
            ForeColor = Color.FromArgb(229, 233, 240),
            Font = new Font("Consolas", 9F),
            Location = new Point(39, 275),
            Size = new Size(680, 280),
            Padding = new Padding(10),
            DetectUrls = false,
            ScrollBars = RichTextBoxScrollBars.Vertical
        };
        Controls.Add(_logBox);

        _updateButton = MakeButton("立即检查并更新", Color.FromArgb(26, 115, 232), Color.White);
        _updateButton.Location = new Point(39, 585);
        _updateButton.Size = new Size(165, 42);
        _updateButton.Click += async (_, _) => await RunUpdateAsync();
        Controls.Add(_updateButton);

        _copyPathButton = MakeButton("复制插件目录", Color.FromArgb(235, 237, 241), Color.FromArgb(55, 60, 70));
        _copyPathButton.Location = new Point(216, 585);
        _copyPathButton.Size = new Size(165, 42);
        _copyPathButton.Click += CopyPathButton_Click;
        Controls.Add(_copyPathButton);

        var hint = new Label
        {
            AutoSize = false,
            Text = "更新后请在扩展管理页点击插件卡片上的刷新按钮",
            TextAlign = ContentAlignment.MiddleRight,
            ForeColor = Color.FromArgb(120, 125, 135),
            Location = new Point(405, 585),
            Size = new Size(314, 42)
        };
        Controls.Add(hint);

        Shown += async (_, _) =>
        {
            if (!_started)
            {
                _started = true;
                await RunUpdateAsync();
            }
        };
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
            TabStop = false
        };
    }

    private async Task RunUpdateAsync()
    {
        if (_running) return;
        _running = true;
        _updateButton.Enabled = false;
        _browseButton.Enabled = false;
        _copyPathButton.Enabled = false;
        _statusLabel.Text = "正在检查更新...";
        _statusLabel.ForeColor = Color.FromArgb(26, 115, 232);
        _progressBar.Style = ProgressBarStyle.Marquee;
        _logBox.Clear();

        try
        {
            var result = await _updateService.RunAsync(_options, AppendLog);
            if (!string.IsNullOrWhiteSpace(result.ExtensionDirectory))
            {
                _pathLabel.Text = result.ExtensionDirectory;
            }
            _progressBar.Style = ProgressBarStyle.Continuous;
            _progressBar.Value = 100;
            _statusLabel.Text = result.Success ? "更新完成" : "更新失败";
            _statusLabel.ForeColor = result.Success
                ? Color.FromArgb(30, 126, 52)
                : Color.FromArgb(197, 34, 31);
        }
        catch (Exception error)
        {
            _statusLabel.Text = "更新失败";
            _statusLabel.ForeColor = Color.FromArgb(197, 34, 31);
            AppendLog("\n" + error.Message + "\n");
        }
        finally
        {
            _running = false;
            _updateButton.Enabled = true;
            _browseButton.Enabled = true;
            _copyPathButton.Enabled = true;
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
        _options = _options with { Path = dialog.SelectedPath };
        _pathLabel.Text = dialog.SelectedPath;
    }

    private void CopyPathButton_Click(object? sender, EventArgs e)
    {
        try
        {
            var path = UpdateService.ResolveExtensionDirectory(_options.Path);
            Clipboard.SetText(path);
            _pathLabel.Text = path;
            _statusLabel.Text = "插件目录已复制到剪贴板";
            _statusLabel.ForeColor = Color.FromArgb(30, 126, 52);
        }
        catch (Exception error)
        {
            _statusLabel.Text = "复制失败";
            _statusLabel.ForeColor = Color.FromArgb(197, 34, 31);
            AppendLog("\n复制插件目录失败: " + error.Message + "\n");
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
