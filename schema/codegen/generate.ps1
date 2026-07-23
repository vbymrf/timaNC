[CmdletBinding()]
param(
    [ValidateSet('All', 'Proto', 'OpenApi')]
    [string]$Target = 'All',
    [string]$OpenApiSpec
)

. (Join-Path $PSScriptRoot 'Common.ps1')

$generateProto = $Target -in @('All', 'Proto')
$generateOpenApi = $Target -in @('All', 'OpenApi')
$protoRoot = Join-Path $script:SchemaRoot 'proto'
$openApiRoot = Join-Path $script:SchemaRoot 'openapi'

if ($generateProto) {
    $protoFiles = @()
    if (Test-Path $protoRoot) {
        $protoFiles = @(Get-ChildItem -Recurse -File -Filter '*.proto' $protoRoot)
    }
    if ($protoFiles.Count -eq 0) {
        throw "No .proto files found in '$protoRoot'. Schemas must be available before code generation."
    }
}

if ($generateOpenApi) {
    if ($OpenApiSpec) {
        $spec = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OpenApiSpec)
        if (-not (Test-Path -PathType Leaf $spec)) {
            throw "OpenAPI document not found: $spec"
        }
    }
    else {
        $candidates = @()
        if (Test-Path $openApiRoot) {
            $candidates = @(Get-ChildItem -File $openApiRoot | Where-Object { $_.Extension -in @('.yaml', '.yml', '.json') })
        }
        if ($candidates.Count -ne 1) {
            throw "Expected exactly one OpenAPI document in '$openApiRoot', found $($candidates.Count). Pass -OpenApiSpec explicitly."
        }
        $spec = $candidates[0].FullName
    }
}

if ($generateProto) {
    $buf = Ensure-CodegenTool 'Buf'
    $java = Ensure-CodegenTool 'Jdk'
    $gradle = Ensure-CodegenTool 'Gradle'
    $protocGenGo = Join-Path $script:ToolsRoot 'bin\protoc-gen-go.exe'
    if (-not (Test-Path $protocGenGo)) {
        $go = Ensure-CodegenTool 'Go'
        $env:GOBIN = Split-Path -Parent $protocGenGo
        New-Item -ItemType Directory -Force $env:GOBIN | Out-Null
        & $go install 'google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.6'
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $protocGenGo)) {
            throw 'Failed to install pinned protoc-gen-go v1.36.6.'
        }
    }
    $javaHome = Split-Path -Parent (Split-Path -Parent $java)
    $goProtoOutput = Join-Path $script:RepoRoot 'gen\go\proto'
    $kotlinProtoOutput = Join-Path $script:RepoRoot 'gen\kotlin\proto'
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $goProtoOutput, $kotlinProtoOutput

    Push-Location $script:SchemaRoot
    try {
        & $buf generate . --template (Join-Path $script:SchemaRoot 'buf.gen.yaml')
        if ($LASTEXITCODE -ne 0) {
            throw "buf generate failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    $oldJavaHome = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = $javaHome
        Push-Location (Join-Path $script:RepoRoot 'gen\kotlin')
        try {
            & $gradle --no-daemon generateProtos
            if ($LASTEXITCODE -ne 0) {
                throw "Square Wire Kotlin generation failed with exit code $LASTEXITCODE."
            }
        }
        finally {
            Pop-Location
        }
    }
    finally {
        $env:JAVA_HOME = $oldJavaHome
    }
}

if ($generateOpenApi) {
    $java = Ensure-CodegenTool 'Jdk'
    $generator = Ensure-CodegenTool 'OpenApiGenerator'
    # Keep the canonical OAS linked to Draft 2020-12 schemas. OpenAPI
    # Generator currently expands nested external $defs incorrectly, so its
    # private codegen view maps the two independently generated document
    # contracts to opaque JSON values.
    $generatorSpec = Join-Path $script:ToolsRoot 'client-api-codegen.yaml'
    $specText = Get-Content -Raw $spec
    $privateRef = '(?m)^    PrivateDocumentEnvelope:\r?\n      \$ref: \.\./json/private-document-envelope\.schema\.json\s*$'
    $publicRef = '(?m)^    PublicDocument:\r?\n      \$ref: \.\./json/document-v2\.schema\.json#/\$defs/publicDocument\s*$'
    if ($specText -notmatch $privateRef -or $specText -notmatch $publicRef) {
        throw 'Expected canonical external DocumentV2 schema references were not found in the OpenAPI document.'
    }
    $specText = $specText -replace $privateRef, "    PrivateDocumentEnvelope:`n      type: object`n      additionalProperties: true"
    $specText = $specText -replace $publicRef, "    PublicDocument:`n      type: object`n      additionalProperties: true"
    [System.IO.File]::WriteAllText($generatorSpec, $specText, [System.Text.UTF8Encoding]::new($false))
    $jobs = @(
        @{
            Config = Join-Path $PSScriptRoot 'openapi-go.yaml'
            Output = Join-Path $script:RepoRoot 'gen\go\openapi'
        },
        @{
            Config = Join-Path $PSScriptRoot 'openapi-kotlin.yaml'
            Output = Join-Path $script:RepoRoot 'gen\kotlin\openapi'
        }
    )
    foreach ($job in $jobs) {
        Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $job.Output
        # OpenAPI Generator's OAS 3.1 parser cannot validate local $defs nested
        # inside external Draft 2020-12 schemas. Contract validation is a
        # separate check; generation must preserve those normative references.
        # OpenAPI Generator also writes recoverable parser diagnostics to
        # stderr. Windows PowerShell turns native stderr into ErrorRecord
        # objects when ErrorActionPreference is Stop, so run the native process
        # with non-terminating stderr handling and trust its exit code.
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $java -jar $generator generate --skip-validate-spec -i $generatorSpec -o $job.Output -c $job.Config
            $generatorExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousErrorAction
        }
        if ($generatorExitCode -ne 0) {
            throw "OpenAPI generation failed for '$($job.Config)' with exit code $generatorExitCode."
        }
    }
}

Write-Host "Generated $Target sources successfully."
