#!/system/bin/sh
# Manual AOT compile (installd's dex2oat is broken on this 4.14 kernel: no pidfd_open)
# usage: aot.sh <package> [filter]
PKG=$1
APK=$(pm path "$PKG" | head -1 | sed s/package://)
[ -z "$APK" ] && { echo "$PKG: not found"; exit 1; }
PROF=/data/misc/profiles/cur/0/$PKG/primary.prof
FILTER=${2:-speed-profile}
TMPPROF=/data/local/tmp/$PKG.prof
PARGS=""
if [ "$FILTER" = speed-profile ]; then
  if [ -s "$PROF" ]; then cp "$PROF" "$TMPPROF"; PARGS="--profile-file=$TMPPROF"; else FILTER=verify; fi
fi
case "$APK" in
  /data/app/*)
    D=$(dirname "$APK")/oat/arm; mkdir -p "$D"
    ODEX=$D/base.odex; ART=$D/base.art; CTX=dalvikcache_data_file ;;
  *)
    N=$(echo "${APK#/}" | sed 's#/#@#g')
    D=/data/dalvik-cache/arm
    ODEX=$D/$N@classes.dex; ART=$D/$N@classes.art; CTX=dalvikcache_data_file ;;
esac
rm -f "$ODEX" "${ODEX%.*}.vdex" "$ART"
/apex/com.android.art/bin/dex2oat32 --dex-file="$APK" --dex-location="$APK" --oat-file="$ODEX" \
  --app-image-file="$ART" --image-format=lz4 \
  --instruction-set=arm --instruction-set-variant=cortex-a53 --instruction-set-features=default \
  --compiler-filter=$FILTER $PARGS --class-loader-context="${CLC:-PCL[]{}}" -j2 \
  --runtime-arg -Xms64m --runtime-arg -Xmx256m >/dev/null 2>&1
RC=$?
rm -f "$TMPPROF"
for f in "$ODEX" "${ODEX%.*}.vdex" "$ART"; do
  [ -f "$f" ] && { chown system:system "$f"; chmod 644 "$f"; chcon u:object_r:$CTX:s0 "$f"; }
done
echo "$PKG: filter=$FILTER rc=$RC"
