param(
    [string]$UsersCsvPath = "C:\Users\PM_User\Downloads\users.csv",
    [string]$BeneficiariesCsvPath = "C:\Users\PM_User\Downloads\bene.csv",
    [string]$TemplatesCsvPath = "C:\Users\PM_User\Downloads\templates.csv",
    [string]$OutputSqlPath = ".\generated\migration_inserts.sql",
    [int]$UserIdStart = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Normalize-Field {
    param([object]$Value)

    if ($null -eq $Value) {
        return $null
    }

    $text = [string]$Value
    $text = $text.Trim().Trim('"').Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }

    return $text
}

function To-SqlString {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return "NULL"
    }

    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return "NULL"
    }

    return "'" + $text.Replace("'", "''") + "'"
}

function To-SqlNumber {
    param(
        [string]$Value,
        [string]$Default = "0.00"
    )

    if ($null -eq $Value) {
        return $Default
    }

    $trimmed = $Value.Trim()
    if ($trimmed -eq "") {
        return $Default
    }

    $normalized = $trimmed
    if ($normalized.StartsWith(".")) {
        $normalized = "0$normalized"
    }

    if ($normalized -match "^-?\d+(\.\d+)?$") {
        return $normalized
    }

    return $Default
}

function To-SqlTimestampOrCurrent {
    param([string]$Value)

    if ($null -eq $Value -or [string]::IsNullOrWhiteSpace($Value)) {
        return "CURRENT_TIMESTAMP"
    }

    return To-SqlString $Value
}

function Parse-IdExpire {
    param([string]$RawValue)

    if ($null -eq $RawValue) {
        return $null
    }

    $digits = ($RawValue -replace "\D", "")
    if ([string]::IsNullOrWhiteSpace($digits)) {
        return $null
    }

    if ($digits.Length -gt 6) {
        return $null
    }

    $padded = $digits.PadLeft(6, "0")
    $parsed = [datetime]::MinValue
    $ok = [datetime]::TryParseExact(
        $padded,
        "ddMMyy",
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::None,
        [ref]$parsed
    )

    if (-not $ok) {
        return $null
    }

    return $parsed.ToString("yyyy-MM-dd")
}

function Parse-DateOfBirth {
    param([string]$RawValue)

    if ($null -eq $RawValue) {
        return $null
    }

    $digits = ($RawValue -replace "\D", "")
    if ([string]::IsNullOrWhiteSpace($digits)) {
        return $null
    }

    if ($digits.Length -eq 7) {
        $yearText = $digits.Substring(0, 4)
        $dayText = $digits.Substring(4, 3)
        $year = 0
        $dayOfYear = 0
        if ([int]::TryParse($yearText, [ref]$year) -and [int]::TryParse($dayText, [ref]$dayOfYear)) {
            if ($dayOfYear -ge 1 -and $dayOfYear -le 366) {
                try {
                    return ([datetime]::new($year, 1, 1).AddDays($dayOfYear - 1)).ToString("yyyy-MM-dd")
                }
                catch {
                    return $null
                }
            }
        }
    }

    $parsed = [datetime]::MinValue
    $formats = @("yyyyMMdd", "ddMMyyyy")
    foreach ($format in $formats) {
        $ok = [datetime]::TryParseExact(
            $digits,
            $format,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::None,
            [ref]$parsed
        )
        if ($ok) {
            return $parsed.ToString("yyyy-MM-dd")
        }
    }

    return $null
}

function Map-IdType {
    param([string]$RawValue)

    if ($null -eq $RawValue) {
        return $null
    }

    if ($RawValue.Trim().ToUpperInvariant() -eq "NIC") {
        return "NID"
    }

    return $RawValue
}

function Map-TemplateTransferType {
    param([string]$RawValue)

    if ($null -eq $RawValue) {
        return $null
    }

    $upper = $RawValue.Trim().ToUpperInvariant()
    if ($upper -eq "INTRABANK" -or $upper -eq "DOMESTIC_PAYMENT") {
        return "LOCAL_TRANSFER"
    }

    return $RawValue
}

function To-UniqueKey {
    param(
        [string]$Value,
        [bool]$IgnoreCase = $false
    )

    if ($null -eq $Value) {
        return $null
    }

    $trimmed = $Value.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        return $null
    }

    if ($IgnoreCase) {
        return $trimmed.ToLowerInvariant()
    }

    return $trimmed
}

function Assert-UniqueUserField {
    param(
        [hashtable]$Seen,
        [string]$RawValue,
        [bool]$IgnoreCase
    )

    $key = To-UniqueKey -Value $RawValue -IgnoreCase $IgnoreCase
    if ($null -eq $key) {
        return $true
    }

    if ($Seen.ContainsKey($key)) {
        return $false
    }

    $Seen[$key] = $true
    return $true
}

function To-SqlBool {
    param([bool]$Value)

    if ($Value) {
        return "TRUE"
    }

    return "FALSE"
}

if (-not (Test-Path -LiteralPath $UsersCsvPath)) {
    throw "Users CSV not found: $UsersCsvPath"
}

if (-not (Test-Path -LiteralPath $BeneficiariesCsvPath)) {
    throw "Beneficiary CSV not found: $BeneficiariesCsvPath"
}

$users = Import-Csv -Path $UsersCsvPath
$beneficiaries = Import-Csv -Path $BeneficiariesCsvPath
$templates = @()
if (-not [string]::IsNullOrWhiteSpace($TemplatesCsvPath) -and (Test-Path -LiteralPath $TemplatesCsvPath)) {
    $templates = Import-Csv -Path $TemplatesCsvPath
}

$sqlLines = New-Object System.Collections.Generic.List[string]
$sqlLines.Add("-- Auto-generated SQL inserts")
$sqlLines.Add("-- Users source: $UsersCsvPath")
$sqlLines.Add("-- Beneficiaries source: $BeneficiariesCsvPath")
if ($templates.Count -gt 0) {
    $sqlLines.Add("-- Templates source: $TemplatesCsvPath")
}
$sqlLines.Add("")

$pendingUserPrefix = 'INSERT INTO "pending_user" ("id", "created_date_time", "updated_date_time", "bank_email", "cif", "city", "country", "digested_password", "email", "email_matched", "email_mismatch_count", "first_name", "full_name", "id_expire", "id_type", "identity_number", "last_name", "middle_name", "mobile", "password_reference", "phase", "preferred_language", "status", "street1", "street2", "street3", "username", "date_of_birth", "registered_account_number", "migrate_user") VALUES '
$nextUserId = $UserIdStart
$userInsertCount = 0
$userSkippedCount = 0

$seenCif = @{}
$seenUsername = @{}
$seenIdentityNumber = @{}
$seenMobile = @{}
$seenEmail = @{}
$userRowNumber = 1

foreach ($row in $users) {
    $userRowNumber++

    $bankEmail = Normalize-Field $row.BANK_EMAIL
    $email = Normalize-Field $row.EMAIL
    if ($null -eq $email) {
        $email = $bankEmail
    }

    $cif = Normalize-Field $row.CIF
    $identityNumber = Normalize-Field $row.IDENTITY_NUMBER
    $mobile = Normalize-Field $row.MOBILE
    $username = Normalize-Field $row.USERNAME
    $registeredAccountNumber = Normalize-Field $row.REGISTERED_ACCOUNT_NUMBER
    $idExpire = Parse-IdExpire (Normalize-Field $row.ID_EXPIRE)
    $dob = Parse-DateOfBirth (Normalize-Field $row.DATE_OF_BIRTH)
    $idType = Map-IdType (Normalize-Field $row.ID_TYPE)

    $duplicateFields = New-Object System.Collections.Generic.List[string]
    if (-not (Assert-UniqueUserField -Seen $seenCif -RawValue $cif -IgnoreCase $false)) { $duplicateFields.Add("cif") }
    if (-not (Assert-UniqueUserField -Seen $seenUsername -RawValue $username -IgnoreCase $true)) { $duplicateFields.Add("username") }
    if (-not (Assert-UniqueUserField -Seen $seenIdentityNumber -RawValue $identityNumber -IgnoreCase $true)) { $duplicateFields.Add("identity_number") }
    if (-not (Assert-UniqueUserField -Seen $seenMobile -RawValue $mobile -IgnoreCase $false)) { $duplicateFields.Add("mobile") }
    if (-not (Assert-UniqueUserField -Seen $seenEmail -RawValue $email -IgnoreCase $true)) { $duplicateFields.Add("email") }

    $skipReasons = @()
    if ($null -eq $registeredAccountNumber) {
        $skipReasons += "missing required field(s): REGISTERED_ACCOUNT_NUMBER"
    }
    if ($duplicateFields.Count -gt 0) {
        $skipReasons += "duplicate " + ($duplicateFields -join ", ")
    }

    if ($skipReasons.Count -gt 0) {
        $sqlLines.Add("-- Skipped users CSV row $userRowNumber due to " + ($skipReasons -join "; "))
        $userSkippedCount++
        continue
    }

    $values = @(
        $nextUserId.ToString(),
        "CURRENT_TIMESTAMP",
        "CURRENT_TIMESTAMP",
        (To-SqlString $bankEmail),
        (To-SqlString $cif),
        "NULL",
        (To-SqlString "MALDIVES"),
        "NULL",
        (To-SqlString $email),
        "NULL",
        "0",
        "NULL",
        (To-SqlString (Normalize-Field $row.FULL_NAME)),
        (To-SqlString $idExpire),
        (To-SqlString $idType),
        (To-SqlString $identityNumber),
        "NULL",
        "NULL",
        (To-SqlString $mobile),
        "NULL",
        (To-SqlString "MIGRATE"),
        "NULL",
        (To-SqlString "IN_PROGRESS"),
        (To-SqlString (Normalize-Field $row.STREET1)),
        (To-SqlString (Normalize-Field $row.STREET2)),
        (To-SqlString (Normalize-Field $row.STREET3)),
        (To-SqlString $username),
        (To-SqlString $dob),
        (To-SqlString $registeredAccountNumber),
        "TRUE"
    )

    $sqlLines.Add($pendingUserPrefix + "(" + ($values -join ", ") + ") ON CONFLICT DO NOTHING;")
    $userInsertCount++
    $nextUserId++
}

$sqlLines.Add("")

$beneficiaryPrefix = 'INSERT INTO "migrate_beneficiary" ("cif", "created_date_time", "updated_date_time", "account_number", "bank_code", "bank_name", "nickname", "predefined_limit", "recipient_name", "transfer_limit", "type", "type_description", "recipient_country", "recipient_country_code", "bank_bic", "is_intra_group", "is_combank") VALUES '
$beneficiaryInsertCount = 0

foreach ($row in $beneficiaries) {
    $typeRaw = (Normalize-Field $row.TYPE)
    $upperType = if ($null -eq $typeRaw) { "" } else { $typeRaw.ToUpperInvariant() }

    $isCombank = $false
    $mappedType = $typeRaw
    if ($upperType -eq "WITHIN_COMBANK") {
        $isCombank = $true
        $mappedType = "LOCAL_TRANSFER"
    }
    elseif ($upperType -eq "OTHER_BANK") {
        $mappedType = "LOCAL_TRANSFER"
    }

    if ($null -eq $mappedType) {
        $mappedType = "LOCAL_TRANSFER"
    }

    $recipientName = Normalize-Field $row.RECIPIENT_NAME
    if ($null -eq $recipientName) {
        $recipientName = Normalize-Field $row.NICKNAME
    }

    $bankCode = Normalize-Field $row.BANK_CODE
    $bankName = $null
    if ($isCombank) {
        $bankName = "Commercial Bank of Maldives"
    }

    $predefinedRaw = Normalize-Field $row.PREDEFINED_LIMIT
    $predefinedLimit = "FALSE"
    if ($null -ne $predefinedRaw) {
        $lower = $predefinedRaw.ToLowerInvariant()
        if ($lower -notmatch "^(0+(\.0+)?|\.0+|false|f|no)$") {
            $predefinedLimit = "TRUE"
        }
    }

    $values = @(
        (To-SqlString (Normalize-Field $row.CIF)),
        "CURRENT_TIMESTAMP",
        "CURRENT_TIMESTAMP",
        (To-SqlString (Normalize-Field $row.ACCOUNT_NUMBER)),
        (To-SqlString $bankCode),
        (To-SqlString $bankName),
        (To-SqlString (Normalize-Field $row.NICKNAME)),
        (To-SqlString $predefinedLimit),
        (To-SqlString $recipientName),
        (To-SqlNumber $predefinedRaw "0.00"),
        (To-SqlString $mappedType),
        (To-SqlString "Fund Transfer"),
        "NULL",
        "NULL",
        (To-SqlString $bankCode),
        (To-SqlBool $isCombank),
        (To-SqlBool $isCombank)
    )

    $sqlLines.Add($beneficiaryPrefix + "(" + ($values -join ", ") + ");")
    $beneficiaryInsertCount++
}

$templateInsertCount = 0
if ($templates.Count -gt 0) {
    $sqlLines.Add("")

    $templatePrefix = 'INSERT INTO "migrate_template" ("created_date_time", "updated_date_time", "amount", "from_account", "note_to_recipient", "personal_note", "recipient_bank", "recipient_name", "template_name", "to_account", "cif", "currency_code", "bank_code", "charges", "purpose", "recipient_country", "transfer_type", "recipient_country_code", "charge_option", "intermediary_bank_swift_code", "recipient_address", "swift_code", "is_combank") VALUES '

    foreach ($row in $templates) {
        $migratedTimestamp = Normalize-Field $row.MIGRATED_TIMESTAMP
        $transferType = Map-TemplateTransferType (Normalize-Field $row.TEMPLATE_TYPE)
        $templateBankCode = Normalize-Field $row.BANK_CODE
        $isTemplateCombank = $templateBankCode -eq "66"
        $intermediarySwift = $null
        if ($row.PSObject.Properties.Match("INTERMEDIARY_BANK_SWIFT_CODE").Count -gt 0) {
            $intermediarySwift = Normalize-Field $row.INTERMEDIARY_BANK_SWIFT_CODE
        }
        if ($null -eq $intermediarySwift) {
            $intermediarySwift = ""
        }

        $values = @(
            (To-SqlTimestampOrCurrent $migratedTimestamp),
            (To-SqlTimestampOrCurrent $migratedTimestamp),
            (To-SqlNumber (Normalize-Field $row.AMOUNT) "0.00"),
            (To-SqlString (Normalize-Field $row.FROM_ACCOUNT)),
            (To-SqlString (Normalize-Field $row.NOTE_TO_RECIPIENT)),
            (To-SqlString (Normalize-Field $row.PERSONAL_NOTE)),
            (To-SqlString (Normalize-Field $row.RECIPIENT_BANK)),
            (To-SqlString (Normalize-Field $row.RECIPIENT_NAME)),
            (To-SqlString (Normalize-Field $row.TEMPLATE_NAME)),
            (To-SqlString (Normalize-Field $row.TO_ACCOUNT)),
            (To-SqlString (Normalize-Field $row.CIF)),
            (To-SqlString (Normalize-Field $row.CURRENCY_CODE)),
            (To-SqlString $templateBankCode),
            "NULL",
            (To-SqlString (Normalize-Field $row.PURPOSE)),
            (To-SqlString (Normalize-Field $row.RECIPIENT_COUNTRY)),
            (To-SqlString $transferType),
            (To-SqlString (Normalize-Field $row.RECIPIENT_COUNTRY_CODE)),
            "NULL",
            (To-SqlString $intermediarySwift),
            (To-SqlString (Normalize-Field $row.RECIPIENT_ADDRESS)),
            (To-SqlString (Normalize-Field $row.SWIFT_CODE)),
            $(if ($isTemplateCombank) { "TRUE" } else { "FALSE" })
        )

        $sqlLines.Add($templatePrefix + "(" + ($values -join ", ") + ");")
        $templateInsertCount++
    }
}

$outputDirectory = Split-Path -Parent $OutputSqlPath
if (-not [string]::IsNullOrWhiteSpace($outputDirectory) -and -not (Test-Path -LiteralPath $outputDirectory)) {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

Set-Content -Path $OutputSqlPath -Value $sqlLines -Encoding UTF8

Write-Host "SQL file generated: $OutputSqlPath"
Write-Host "User inserts: $userInsertCount"
Write-Host "User rows skipped (duplicate unique fields): $userSkippedCount"
Write-Host "Beneficiary inserts: $beneficiaryInsertCount"
Write-Host "Template inserts: $templateInsertCount"
