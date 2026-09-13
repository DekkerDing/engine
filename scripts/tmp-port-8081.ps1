$c = Get-NetTCPConnection -LocalPort 8081 -ErrorAction SilentlyContinue
if ($c) {
  $c | ForEach-Object {
    $p = Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue
    "{0}  PID={1}  进程={2}  启动时间={3}" -f $_.State, $_.OwningProcess, $p.ProcessName, $p.StartTime
  }
} else {
  "8081 无任何连接（含 Bound）"
}
