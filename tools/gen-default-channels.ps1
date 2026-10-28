# 从 iptv-org 的中国频道列表生成 App 内置默认源（res/raw/channels.txt）
# 只在需要刷新内置频道时手动执行：powershell -File tools/gen-default-channels.ps1
param(
    [string]$InputM3u = (Join-Path $PSScriptRoot 'source-iptv-org-cn.m3u'),
    [string]$OutputTxt = (Join-Path $PSScriptRoot '../app/src/main/res/raw/channels.txt')
)

# tvg-id -> 分组, 中文台名。顺序即界面显示顺序
$map = [ordered]@{
    # 央视
    'CCTV1.cn@HD'                  = @('央视', 'CCTV-1 综合')
    'CCTV2.cn@SD'                  = @('央视', 'CCTV-2 财经')
    'CCTV3.cn@SD'                  = @('央视', 'CCTV-3 综艺')
    'CCTV4K.cn@HD'                 = @('央视', 'CCTV-4K 超高清')
    'CCTV5Plus.cn@SD'              = @('央视', 'CCTV-5+ 体育赛事')
    'CCTV6.cn@HD'                  = @('央视', 'CCTV-6 电影')
    'CCTV7.cn@SD'                  = @('央视', 'CCTV-7 国防军事')
    'CCTV8.cn@SD'                  = @('央视', 'CCTV-8 电视剧')
    'CCTV9.cn@SD'                  = @('央视', 'CCTV-9 纪录')
    'CCTV10.cn@SD'                 = @('央视', 'CCTV-10 科教')
    'CCTV11.cn@SD'                 = @('央视', 'CCTV-11 戏曲')
    'CCTV12.cn@SD'                 = @('央视', 'CCTV-12 社会与法')
    'CCTV13.cn@HD'                 = @('央视', 'CCTV-13 新闻')
    'CCTV14.cn@HD'                 = @('央视', 'CCTV-14 少儿')
    'CCTV15.cn@SD'                 = @('央视', 'CCTV-15 音乐')
    'CCTV16.cn@HD'                 = @('央视', 'CCTV-16 奥林匹克')
    'CCTV17.cn@HD'                 = @('央视', 'CCTV-17 农业农村')
    # 卫视
    'BeijingSatelliteTV.cn@HD'     = @('卫视', '北京卫视')
    'DragonTVInternational.cn@SD'  = @('卫视', '东方卫视国际')
    'HunanTV.cn@SD'                = @('卫视', '湖南卫视')
    'ZhejiangSatelliteTV.cn@SD'    = @('卫视', '浙江卫视')
    'JiangsuSatelliteTV.cn@SD'     = @('卫视', '江苏卫视')
    'GuangdongSatelliteTV.cn@SD'   = @('卫视', '广东卫视')
    'ShenzhenSatelliteTV.cn@SD'    = @('卫视', '深圳卫视')
    'AnhuiTV.cn@SD'                = @('卫视', '安徽卫视')
    'ShandongSatelliteTV.cn@SD'    = @('卫视', '山东卫视')
    'TianjinTV.cn@SD'              = @('卫视', '天津卫视')
    'SichuanSatelliteTV.cn@SD'     = @('卫视', '四川卫视')
    'HubeiTV.cn@SD'                = @('卫视', '湖北卫视')
    'FujianComprehensiveChannel.cn@SD' = @('卫视', '东南卫视')
    'JiangxiTV.cn@SD'              = @('卫视', '江西卫视')
    'LiaoningTV.cn@SD'             = @('卫视', '辽宁卫视')
    'HeilongjiangTV.cn@SD'         = @('卫视', '黑龙江卫视')
    'YanbianSatelliteTV.cn@SD'     = @('卫视', '延边卫视')
    'HebeiTV.cn@SD'                = @('卫视', '河北卫视')
    'GuizhouTV.cn@SD'              = @('卫视', '贵州卫视')
    'YunnanSatelliteTV.cn@SD'      = @('卫视', '云南卫视')
    'NingxiaSatelliteChannel.cn@SD' = @('卫视', '宁夏卫视')
    'QinghaiTV.cn@SD'              = @('卫视', '青海卫视')
    'XinjiangTV1.cn@SD'            = @('卫视', '新疆卫视')
    'XizangTVChinese.cn@SD'        = @('卫视', '西藏卫视')
    'NeiMonggolTV.cn@SD'           = @('卫视', '内蒙古卫视')
    'BingtuanSatelliteTV.cn@SD'    = @('卫视', '兵团卫视')
    'KangbaTV.cn@SD'               = @('卫视', '康巴卫视')
    # 教育
    'CETV1.cn@SD'                  = @('教育', '中国教育1台')
    'CETV2.cn@SD'                  = @('教育', '中国教育2台')
}

$entries = @{}
$current = $null
foreach ($line in [System.IO.File]::ReadAllLines($InputM3u)) {
    $t = $line.Trim()
    if ($t.StartsWith('#EXTINF')) {
        $id = if ($t -match 'tvg-id="([^"]*)"') { $Matches[1] } else { '' }
        $logo = if ($t -match 'tvg-logo="([^"]*)"') { $Matches[1] } else { '' }
        $current = @{ id = $id; logo = $logo }
    }
    elseif ($t -ne '' -and -not $t.StartsWith('#') -and $null -ne $current) {
        if ($current.id -ne '' -and -not $entries.ContainsKey($current.id)) {
            $entries[$current.id] = @{ logo = $current.logo; url = $t }
        }
        $current = $null
    }
}

$sb = [System.Text.StringBuilder]::new()
[void]$sb.Append("#EXTM3U`n")
$written = 0
foreach ($id in $map.Keys) {
    $e = $entries[$id]
    if ($null -eq $e) { Write-Warning "source list has no entry for $id"; continue }
    $group = $map[$id][0]
    $name = $map[$id][1]
    [void]$sb.Append("#EXTINF:-1 tvg-id=`"$id`" tvg-name=`"$name`" tvg-logo=`"$($e.logo)`" group-title=`"$group`",$name`n")
    [void]$sb.Append("$($e.url)`n")
    $written++
}

[System.IO.File]::WriteAllText($OutputTxt, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Host "wrote $written channels -> $OutputTxt"
