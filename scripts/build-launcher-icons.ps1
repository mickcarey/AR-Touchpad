[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$iconProjectRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.Drawing
$iconSource = [System.Drawing.Image]::FromFile((Join-Path $iconProjectRoot 'docs/assets/app-icon-master.png'))
function Save-IconSize([int]$Size, [string]$Destination) {
    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.DrawImage($iconSource, 0, 0, $Size, $Size)
        $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    # minSdk 34 supports adaptive icons everywhere; no legacy PNG launcher fallbacks.
    Save-IconSize 512 (Join-Path $iconProjectRoot 'app/src/main/res/drawable-nodpi/launcher_base.png')
} finally { $iconSource.Dispose() }
