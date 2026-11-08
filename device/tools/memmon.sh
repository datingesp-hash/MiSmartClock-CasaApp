#!/system/bin/sh
# temporary diagnostic: log memory + GPU/ION every 5 s
L=/data/local/tmp/memlog.txt
[ -f $L ] && mv $L $L.old
echo "t avail_mb lowfree_mb swapused_mb zram_mb ion_mb gpu_mb gpumax_mb top_gpu" > $L
while true; do
  m=$(awk '/MemAvailable/{a=$2}/LowFree/{l=$2}/SwapTotal/{st=$2}/SwapFree/{sf=$2}END{printf "%d %d %d",a/1024,l/1024,(st-sf)/1024}' /proc/meminfo)
  z=$(awk '{printf "%d",$3/1048576}' /sys/block/zram0/mm_stat)
  i=$(awk -F'[][]' '/^total sz/{printf "%d",$2/1048576}' /sys/kernel/debug/ion/heaps/ion_mm_heap)
  g=$(awk '/^MemoryUsageAllocGPUMemUMA /{c=$2}/^MemoryUsageAllocGPUMemUMAMax/{x=$2}END{printf "%d %d",c/1048576,x/1048576}' /sys/kernel/debug/pvr/driver_stats)
  top=$(tail -n +2 /sys/kernel/debug/pvr/memtrack_stats | sort -t, -k6 -nr | head -3 | while IFS=, read p a b c d e; do printf "%s:%dM " "$(cat /proc/$p/comm 2>/dev/null)" $((e/1048576)); done)
  echo "$(cut -d. -f1 /proc/uptime) $m $z $i $g $top" >> $L
  sync
  sleep 5
done
