#!/bin/sh
translator=$1
wineserver=$2
wine=$3
shift 3
exec 3<&0

watcher=
pulse=
busybox=/bin/busybox
[ -x /usr/local/bin/busybox ] && busybox=/usr/local/bin/busybox
stop_file=$("$busybox" mktemp "${TMPDIR:-/tmp}/buran-box-stop.XXXXXX") || exit 1
trap '"$busybox" rm -f "$stop_file"' EXIT

stop_server() {
    "$translator" "$wineserver" -k >/dev/null 2>&1
    local status=$?
    [ "$status" -le 1 ] || return "$status"
    "$translator" "$wineserver" -w >/dev/null 2>&1
}

finish_wine() {
    [ -z "$watcher" ] || kill "$watcher" 2>/dev/null
    # Wine saves the registry before PRoot reaps the remaining tracees.
    stop_server
    local status=$?
    if [ -n "$pulse" ]; then
        kill "$pulse" 2>/dev/null
        wait "$pulse" 2>/dev/null
    fi
    return "$status"
}
# Keep one listener through MSI setup and application launch. EOF may occur
# before Wine has exited, so only a stop request cancels the session.
(
    while IFS= read -r line; do
        if [ "$line" = BURAN_STOP ]; then
            printf '%s\n' stop > "$stop_file" || exit 1
            printf '%s\n' 'Buran: stopping Wine server for this session' >&2
            stop_server
            break
        fi
    done
) <&3 &
watcher=$!

start_audio() {
    export PULSE_RUNTIME_PATH="${BURAN_AUDIO_SOCKET%/*}"
    export PULSE_STATE_PATH="$PULSE_RUNTIME_PATH"
    export PULSE_SERVER="unix:$PULSE_RUNTIME_PATH/pulse"
    export PULSE_CLIENTCONFIG="$PULSE_RUNTIME_PATH/pulse-client.conf"
    printf 'autospawn = no\nenable-shm = no\n' > "$PULSE_CLIENTCONFIG" || return 1
    /usr/bin/pulseaudio -n --daemonize=no --use-pid-file=no --exit-idle-time=-1 \
        --disable-shm=yes --high-priority=no --realtime=no --log-target=stderr \
        -L "module-null-sink sink_name=buran format=s16le rate=48000 channels=2" \
        -L "module-native-protocol-unix socket=$PULSE_RUNTIME_PATH/pulse auth-anonymous=yes auth-cookie-enabled=no" \
        -L "module-simple-protocol-unix socket=$BURAN_AUDIO_SOCKET source=buran.monitor record=yes playback=no format=s16le rate=48000 channels=2" \
        </dev/null &
    pulse=$!
    local attempt=0
    while [ "$attempt" -lt 100 ] && kill -0 "$pulse" 2>/dev/null; do
        [ ! -S "$BURAN_AUDIO_SOCKET" ] || return 0
        /usr/bin/sleep 0.02
        attempt=$((attempt + 1))
    done
    return 1
}
if [ -n "${BURAN_AUDIO_SOCKET:-}" ] && ! start_audio; then
    printf 'Buran: PulseAudio setup failed\n' >&2
fi

run_wine() {
    [ ! -s "$stop_file" ] || return 143
    local overrides=$1
    shift
    if [ -n "${steam_dependency_timeout:-}" ]; then
        LD_PRELOAD="${game_preload:-}" WINEDLLOVERRIDES="$overrides" \
            "$busybox" timeout "$steam_dependency_timeout" "$translator" "$wine" "$@" </dev/null
        return $?
    fi
    LD_PRELOAD="${game_preload:-}" WINEDLLOVERRIDES="$overrides" "$translator" "$wine" "$@" </dev/null
}

if [ -n "${BURAN_GAMEPAD_SHM:-}" ]; then
    # Wineboot can start the persistent Wine HID service before the game.
    game_preload=/usr/local/lib/libburan-gamepad.so
fi
if [ -n "${BURAN_KGSL_PRELOAD:-}" ]; then
    game_preload="${game_preload:+$game_preload:}$BURAN_KGSL_PRELOAD"
fi

result=0
steam_dependency_progress() {
    [ -z "${BURAN_STEAM_DEPENDENCY_PROGRESS:-}" ] ||
        printf '%s\n' "$1" > "$BURAN_STEAM_DEPENDENCY_PROGRESS"
}
steam_dependency_reg() {
    local view=$1
    shift
    [ "$view" = default ] || set -- "$@" "$view"
    steam_dependency_timeout=30 run_wine "$WINEDLLOVERRIDES" reg "$@"
}
steam_dependency_has_marker() {
    local view marker
    for view in $views; do
        marker=$(steam_dependency_reg "$view" query "$key" /v "$name" 2>/dev/null |
            "$busybox" awk '/REG_DWORD[[:space:]]+0x[[:xdigit:]]+/ {print $NF; exit}' |
            "$busybox" tr -d '\r')
        if [ -n "$marker" ] && [ "$((marker))" -ge "$minimum" ]; then
            return 0
        fi
    done
    return 1
}
setup_steam_dependencies() {
    [ -f "$WINEPREFIX/system.reg" ] || run_wine "$WINEDLLOVERRIDES;mscoree=d" wineboot -u || return 1
    while IFS= read -r kind; do
        case "$kind" in
            installer)
                IFS= read -r key && IFS= read -r name && IFS= read -r minimum &&
                    IFS= read -r views && IFS= read -r directory || return 1
                ;;
            registry) ;;
            *) return 1 ;;
        esac
        IFS= read -r count || return 1
        set --
        while [ "$count" -gt 0 ]; do
            IFS= read -r argument || return 1
            set -- "$@" "$argument"
            count=$((count - 1))
        done
        if [ "$kind" = registry ]; then
            steam_dependency_timeout=30 run_wine "$WINEDLLOVERRIDES" "$@" || return 1
            continue
        fi
        if steam_dependency_has_marker; then
            printf 'Buran: Steam dependency already installed for this prefix: %s\n' "$name"
            continue
        fi
        printf 'Buran: installing local Steam dependency: %s\n' "$name"
        steam_dependency_progress INSTALLING || return 1
        status=0
        (cd "$directory" && steam_dependency_timeout=180 run_wine "$WINEDLLOVERRIDES" "$@") || status=$?
        if [ "$status" -eq 0 ]; then
            if ! steam_dependency_has_marker; then
                steam_dependency_reg "${views%% *}" add "$key" /v "$name" /t REG_DWORD /d "$minimum" /f ||
                    return 1
            fi
            printf 'Buran: Steam dependency installed for this prefix: %s\n' "$name"
        else
            printf 'Buran: Steam dependency %s failed (exit %s); will retry next launch\n' "$name" "$status" >&2
            case "$status" in 124|137|143) return 1 ;; esac
        fi
    done < "$BURAN_STEAM_DEPENDENCIES"
}
if [ -n "${BURAN_STEAM_DEPENDENCIES:-}" ]; then
    if setup_steam_dependencies; then
        steam_dependency_progress COMPLETE || result=1
    else
        result=1
    fi
fi
if [ "$result" -eq 0 ] && [ -n "${BURAN_DXVK_DLLS:-}" ]; then
    if [ ! -f "$WINEPREFIX/system.reg" ]; then
        run_wine "$WINEDLLOVERRIDES;mscoree=d" wineboot -u
        result=$?
    fi
    system32="$WINEPREFIX/drive_c/windows/system32"
    for name in ${BURAN_DXVK_NAMES:-d3d11.dll dxgi.dll}; do
        [ "$result" -eq 0 ] || break
        source="$BURAN_DXVK_DLLS/$name"
        busybox=/usr/local/bin/busybox
        if ! "$busybox" cmp -s "$source" "$system32/$name"; then
            "$busybox" cp "$source" "$system32/$name.buran-tmp" &&
                "$busybox" mv -f "$system32/$name.buran-tmp" "$system32/$name" || result=1
        fi
    done
    if [ "$result" -ne 0 ]; then
        printf 'Buran: DXVK setup failed (exit %s)\n' "$result" >&2
    fi
fi
if [ "$result" -eq 0 ] && [ -n "${BURAN_MONO_MSI:-}" ]; then
    mono="$WINEPREFIX/drive_c/windows/mono/mono-2.0"
    marker="$WINEPREFIX/.buran-mono-installed"
    installed=
    [ ! -f "$marker" ] || IFS= read -r installed < "$marker"
    mono_files_present() {
        [ -f "$mono/bin/libmono-2.0-x86_64.dll" ] && [ -f "$mono/lib/mono/4.5/mscorlib.dll" ]
    }
    if [ "$installed" != "$BURAN_MONO_INSTALLER" ] || ! mono_files_present; then
        # Suppress Wine's automatic Mono prompt during prefix creation and MSI setup.
        run_wine "$WINEDLLOVERRIDES;mscoree=d" msiexec /i "$BURAN_MONO_MSI" /qn /norestart
        result=$?
        if [ "$result" -eq 0 ] && mono_files_present; then
            printf '%s\n' "$BURAN_MONO_INSTALLER" > "$marker" || result=1
        else
            printf 'Buran: Wine Mono setup failed (msiexec exit %s)\n' "$result" >&2
            result=1
        fi
    fi
fi
if [ "$result" -eq 0 ]; then
    if [ -n "${BURAN_DXVK_DLLS:-}" ]; then
        WINEDLLOVERRIDES="$WINEDLLOVERRIDES;${BURAN_DXVK_OVERRIDES:-d3d11,dxgi=n}"
    fi
    run_wine "$WINEDLLOVERRIDES" "$@"
    result=$?
fi
finish_wine || {
    printf '%s\n' 'Buran: could not confirm Wine server shutdown' >&2
    exit 1
}
[ ! -s "$stop_file" ] || exit 143
exit "$result"
