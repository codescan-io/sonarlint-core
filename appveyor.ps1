
function FetchAndUnzip
{
        param ([string]$Url, [string]$Out)

        $tmp = [System.IO.Path]::GetTempFileName()
        [System.Reflection.Assembly]::LoadWithPartialName('System.Net.Http') | Out-Null
        $client = (New-Object System.Net.Http.HttpClient)
        try
        {
                if (-not([string]::IsNullOrEmpty($env:GITHUB_TOKEN)))
                {
                        $credentials = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, "{0}:", $env:GITHUB_TOKEN);
                        $credentials = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes($credentials));
                        $client.DefaultRequestHeaders.Authorization = (New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Basic", $credentials));
                }
                $contents = $client.GetByteArrayAsync($url).Result;
                [System.IO.File]::WriteAllBytes($tmp, $contents);
        }
        finally
        {
                $client.Dispose()
        }

        if (-not(Test-Path $Out))
        {
                mkdir $Out | Out-Null
        }
        [System.Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null
        [System.IO.Compression.ZipFile]::ExtractToDirectory($tmp, $Out)
}

function InstallAppveyorTools
{
        $travisUtilsVersion = "36"
        $localPath = "$env:USERPROFILE\.local"
        $travisUtilsPath = "$localPath\travis-utils-$travisUtilsVersion"
        if (Test-Path $travisUtilsPath)
        {
                echo "Reusing the Travis Utils version $travisUtilsVersion already downloaded under $travisUtilsPath"
        }
        else
        {
                $url = "https://github.com/SonarSource/travis-utils/archive/v$travisUtilsVersion.zip"
                echo "Downloading Travis Utils version $travisUtilsVersion from $url into $localPath"
                FetchAndUnzip $url $localPath
        }

        $mavenLocal = "$env:USERPROFILE\.m2"
	if (-not(Test-Path $mavenLocal))
	{
		mkdir $mavenLocal | Out-Null
	}
	echo "Installating Travis Utils private Maven settings.xml into $mavenLocal"
	Copy-Item "$travisUtilsPath\m2\settings-private.xml" "$mavenLocal\settings.xml"

}

function Build
{
        param ([string]$Project, [string]$Sha1)

        $msg = "Fetch [" + $Project + ":" + $Sha1 + "]"
        echo $msg

        $url = "https://github.com/$Project/archive/$Sha1.zip"
        $tmp = "c:\snapshot"
        if (Test-Path $tmp)
        {
                Cmd /C "rmdir /S /Q $tmp"
        }

        FetchAndUnzip $url $tmp

        $msg = "Build [" + $Project + ":" + $Sha1 + "]"
        echo $msg

        pushd $tmp\*
        try
        {
                mvn install "--batch-mode" "-DskipTests" "-Pdev"
                CheckLastExitCode
        }
        finally
        {
                popd
        }
}

function BuildSnapshot
{
        param ([string]$Project)

        echo "Fetch and build snapshot of [$Project]"

        Build $Project "HEAD"
}

function CheckLastExitCode
{
    param ([int[]]$SuccessCodes = @(0))

    if ($SuccessCodes -notcontains $LastExitCode)
        {
        $msg = @"
EXE RETURNED EXIT CODE $LastExitCode
CALLSTACK:$(Get-PSCallStack | Out-String)
"@
        throw $msg
    }
}

InstallAppveyorTools
$env:MAVEN_OPTS = "-Xmx1024m"
mvn verify "--batch-mode"
CheckLastExitCode
