Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  $cmd = $_.CommandLine
  if ($null -ne $cmd -and $cmd.Length -gt 170) { $cmd = $cmd.Substring(0, 170) }
  "{0}`t{1}" -f $_.ProcessId, $cmd
}
