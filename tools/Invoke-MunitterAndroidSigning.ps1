[CmdletBinding()]
param(
    [ValidateSet('Initialize', 'Status', 'Gradle')]
    [string]$Mode = 'Status',
    [string[]]$GradleArguments = @(),
    [string]$SigningRoot = (Join-Path $env:LOCALAPPDATA 'Munitter\AndroidSigning')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Security.Cryptography.ProtectedData -ErrorAction SilentlyContinue

$schema = 'munitter-android-signing/v1'
$envelopePath = Join-Path $SigningRoot 'signing-secrets.dpapi'
$developmentKeystore = Join-Path $SigningRoot 'munitter-development.jks'
$productionKeystore = Join-Path $SigningRoot 'munitter-production-upload.jks'
$entropy = [Text.Encoding]::UTF8.GetBytes('Munitter.Android.Signing.v1')

function Protect-DirectoryAcl {
    param([Parameter(Mandatory)][string]$Path)

    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $allowedSids = @($identity.Value, 'S-1-5-18', 'S-1-5-32-544')
    $currentAcl = Get-Acl -LiteralPath $Path
    $unexpected = @($currentAcl.Access | Where-Object {
        $translated = try {
            $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
        }
        catch {
            $_.IdentityReference.Value
        }
        $translated -notin $allowedSids
    })
    if ($currentAcl.AreAccessRulesProtected -and $unexpected.Count -eq 0) {
        return
    }

    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @(
        $identity,
        [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
        [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    )) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit',
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        [void]$acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function New-SecretValue {
    $bytes = [byte[]]::new(48)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Write-ProtectedEnvelope {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Values)

    $plain = [Text.Encoding]::UTF8.GetBytes(($Values | ConvertTo-Json -Compress))
    try {
        $cipher = [Security.Cryptography.ProtectedData]::Protect(
            $plain,
            $entropy,
            [Security.Cryptography.DataProtectionScope]::CurrentUser)
        [IO.File]::WriteAllBytes($envelopePath, $cipher)
    }
    finally {
        [Array]::Clear($plain, 0, $plain.Length)
    }
}

function Read-ProtectedEnvelope {
    if (-not (Test-Path -LiteralPath $envelopePath -PathType Leaf)) {
        throw 'android-signing-envelope-not-set'
    }
    $cipher = [IO.File]::ReadAllBytes($envelopePath)
    $plain = [Security.Cryptography.ProtectedData]::Unprotect(
        $cipher,
        $entropy,
        [Security.Cryptography.DataProtectionScope]::CurrentUser)
    try {
        $json = [Text.Encoding]::UTF8.GetString($plain)
        $json | ConvertFrom-Json -AsHashtable
    }
    finally {
        [Array]::Clear($plain, 0, $plain.Length)
    }
}

function Invoke-KeyGeneration {
    param(
        [Parameter(Mandatory)][string]$Keystore,
        [Parameter(Mandatory)][string]$Alias,
        [Parameter(Mandatory)][string]$StorePassword,
        [Parameter(Mandatory)][string]$KeyPassword,
        [Parameter(Mandatory)][string]$CommonName
    )

    $oldStorePassword = $env:MUNITTER_KEYTOOL_STORE_PASSWORD
    $oldKeyPassword = $env:MUNITTER_KEYTOOL_KEY_PASSWORD
    try {
        $env:MUNITTER_KEYTOOL_STORE_PASSWORD = $StorePassword
        $env:MUNITTER_KEYTOOL_KEY_PASSWORD = $KeyPassword
        & keytool -genkeypair -noprompt `
            -keystore $Keystore -storetype JKS `
            -storepass:env MUNITTER_KEYTOOL_STORE_PASSWORD `
            -keypass:env MUNITTER_KEYTOOL_KEY_PASSWORD `
            -alias $Alias -keyalg RSA -keysize 4096 -sigalg SHA256withRSA `
            -validity 36500 -dname "CN=$CommonName, OU=Mobile, O=Munitter, C=JP"
        if ($LASTEXITCODE -ne 0) { throw 'android-signing-key-generation-failed' }
    }
    finally {
        $env:MUNITTER_KEYTOOL_STORE_PASSWORD = $oldStorePassword
        $env:MUNITTER_KEYTOOL_KEY_PASSWORD = $oldKeyPassword
    }
}

function Get-SafeCertificateStatus {
    param(
        [Parameter(Mandatory)][System.Collections.IDictionary]$Values,
        [Parameter(Mandatory)][ValidateSet('Development', 'Production')][string]$Environment
    )

    $prefix = $Environment.ToLowerInvariant()
    $keystore = [string]$Values["${prefix}Keystore"]
    $alias = [string]$Values["${prefix}Alias"]
    $password = [string]$Values["${prefix}StorePassword"]
    $old = $env:MUNITTER_KEYTOOL_STORE_PASSWORD
    try {
        $env:MUNITTER_KEYTOOL_STORE_PASSWORD = $password
        $pem = (& keytool -exportcert -rfc -keystore $keystore `
            -storepass:env MUNITTER_KEYTOOL_STORE_PASSWORD -alias $alias 2>$null) -join "`n"
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pem)) {
            throw 'android-signing-certificate-read-failed'
        }
        $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPem($pem)
        try {
            $sha256Bytes = [Security.Cryptography.SHA256]::HashData($certificate.RawData)
            $sha256 = ($sha256Bytes | ForEach-Object { $_.ToString('X2') }) -join ':'
            $sha1 = ($certificate.GetCertHash() | ForEach-Object { $_.ToString('X2') }) -join ':'
            [ordered]@{
                environment = $Environment
                keystore = if (Test-Path -LiteralPath $keystore) { 'SET' } else { 'NOT SET' }
                alias = 'SET'
                sha1 = $sha1
                sha256 = $sha256
                validFrom = $certificate.NotBefore.ToUniversalTime().ToString('O')
                validUntil = $certificate.NotAfter.ToUniversalTime().ToString('O')
            }
        }
        finally {
            $certificate.Dispose()
        }
    }
    finally {
        $env:MUNITTER_KEYTOOL_STORE_PASSWORD = $old
    }
}

function Clear-SecretValues {
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Values)
    foreach ($key in @(
        'developmentStorePassword',
        'developmentKeyPassword',
        'productionStorePassword',
        'productionKeyPassword'
    )) {
        if ($Values.Contains($key)) { $Values[$key] = $null }
    }
}

if ($Mode -eq 'Initialize') {
    if (Test-Path -LiteralPath $envelopePath -PathType Leaf) {
        throw 'android-signing-already-initialized'
    }
    [IO.Directory]::CreateDirectory($SigningRoot) | Out-Null
    Protect-DirectoryAcl -Path $SigningRoot

    $values = [ordered]@{
        schema = $schema
        createdAtUtc = [DateTimeOffset]::UtcNow.ToString('O')
        developmentKeystore = $developmentKeystore
        developmentAlias = 'munitter-development'
        developmentStorePassword = New-SecretValue
        developmentKeyPassword = New-SecretValue
        productionKeystore = $productionKeystore
        productionAlias = 'munitter-production-upload'
        productionStorePassword = New-SecretValue
        productionKeyPassword = New-SecretValue
    }
    try {
        Invoke-KeyGeneration -Keystore $developmentKeystore `
            -Alias $values.developmentAlias `
            -StorePassword $values.developmentStorePassword `
            -KeyPassword $values.developmentKeyPassword `
            -CommonName 'Munitter Android Development'
        Invoke-KeyGeneration -Keystore $productionKeystore `
            -Alias $values.productionAlias `
            -StorePassword $values.productionStorePassword `
            -KeyPassword $values.productionKeyPassword `
            -CommonName 'Munitter Android Production Upload'
        Write-ProtectedEnvelope -Values $values
    }
    catch {
        foreach ($path in @($developmentKeystore, $productionKeystore, $envelopePath)) {
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                [IO.File]::Delete($path)
            }
        }
        throw
    }
    finally {
        Clear-SecretValues -Values $values
    }
}

$secretValues = Read-ProtectedEnvelope
try {
    if ($secretValues.schema -ne $schema) { throw 'android-signing-schema-mismatch' }
    if ($secretValues.developmentKeystore -eq $secretValues.productionKeystore) {
        throw 'android-signing-environment-collision'
    }

    if ($Mode -in @('Initialize', 'Status')) {
        $acl = Get-Acl -LiteralPath $SigningRoot
        $allowedSids = @(
            [Security.Principal.WindowsIdentity]::GetCurrent().User.Value,
            'S-1-5-18',
            'S-1-5-32-544'
        )
        $unexpected = @($acl.Access | Where-Object {
            $translated = try {
                $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            }
            catch {
                $_.IdentityReference.Value
            }
            $translated -notin $allowedSids
        })
        [ordered]@{
            schema = $schema
            secretEnvelope = 'SET'
            acl = if ($unexpected.Count -eq 0 -and $acl.AreAccessRulesProtected) { 'PASS' } else { 'FAIL' }
            credentialsSeparated = $true
            credentialsGitTracked = $false
            development = Get-SafeCertificateStatus -Values $secretValues -Environment Development
            production = Get-SafeCertificateStatus -Values $secretValues -Environment Production
        } | ConvertTo-Json -Depth 4
        exit 0
    }

    if ($GradleArguments.Count -eq 0) { throw 'gradle-arguments-required' }
    $names = @(
        'MUNITTER_ANDROID_DEVELOPMENT_KEYSTORE',
        'MUNITTER_ANDROID_DEVELOPMENT_STORE_PASSWORD',
        'MUNITTER_ANDROID_DEVELOPMENT_KEY_ALIAS',
        'MUNITTER_ANDROID_DEVELOPMENT_KEY_PASSWORD',
        'MUNITTER_ANDROID_PRODUCTION_KEYSTORE',
        'MUNITTER_ANDROID_PRODUCTION_STORE_PASSWORD',
        'MUNITTER_ANDROID_PRODUCTION_KEY_ALIAS',
        'MUNITTER_ANDROID_PRODUCTION_KEY_PASSWORD'
    )
    $oldValues = @{}
    foreach ($name in $names) {
        $oldValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        $env:MUNITTER_ANDROID_DEVELOPMENT_KEYSTORE = $secretValues.developmentKeystore
        $env:MUNITTER_ANDROID_DEVELOPMENT_STORE_PASSWORD = $secretValues.developmentStorePassword
        $env:MUNITTER_ANDROID_DEVELOPMENT_KEY_ALIAS = $secretValues.developmentAlias
        $env:MUNITTER_ANDROID_DEVELOPMENT_KEY_PASSWORD = $secretValues.developmentKeyPassword
        $env:MUNITTER_ANDROID_PRODUCTION_KEYSTORE = $secretValues.productionKeystore
        $env:MUNITTER_ANDROID_PRODUCTION_STORE_PASSWORD = $secretValues.productionStorePassword
        $env:MUNITTER_ANDROID_PRODUCTION_KEY_ALIAS = $secretValues.productionAlias
        $env:MUNITTER_ANDROID_PRODUCTION_KEY_PASSWORD = $secretValues.productionKeyPassword
        & .\gradlew.bat @GradleArguments
        exit $LASTEXITCODE
    }
    finally {
        foreach ($name in $names) {
            [Environment]::SetEnvironmentVariable($name, $oldValues[$name], 'Process')
        }
    }
}
finally {
    Clear-SecretValues -Values $secretValues
}
