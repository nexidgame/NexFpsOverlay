echo '#GPU'
for f in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpubusy /sys/class/misc/mali0/device/utilisation /sys/kernel/gpu/gpu_busy /sys/kernel/ged/hal/gpu_utilization /sys/devices/platform/mali.0/utilization /sys/devices/platform/soc/*.mali/utilization /sys/devices/platform/*.mali/utilization /sys/class/devfreq/*gpu*/gpu_load /sys/class/devfreq/*mali*/load; do
  if [ -r "$f" ]; then echo "G:$f:$(head -n 1 "$f" 2>/dev/null)"; fi
done
for f in /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/clock_mhz /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/misc/mali0/device/clock /sys/kernel/gpu/gpu_clock /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*mali*/cur_freq /sys/devices/platform/*.mali/devfreq/*/cur_freq /sys/kernel/ged/hal/current_freqency; do
  if [ -r "$f" ]; then echo "F:$f:$(head -n 1 "$f" 2>/dev/null)"; fi
done
