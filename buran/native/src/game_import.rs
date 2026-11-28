//! Import an Android document tree into the app's reusable game library.
use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read};
use std::os::fd::AsRawFd;
#[cfg(any(target_os = "android", test))]
use std::os::fd::BorrowedFd;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn relative_path(path: &Path) -> io::Result<&Path> {
    let name = path
        .to_str()
        .ok_or_else(|| invalid("game path requires UTF-8"))?;
    if name.len() > 4095
        || name.split('/').any(|part| {
            part.is_empty()
                || part == "."
                || part == ".."
                || part.len() > 255
                || part.chars().any(|ch| {
                    ch.is_control() || matches!(ch, '\\' | ':' | '<' | '>' | '"' | '|' | '?' | '*')
                })
        })
    {
        return Err(invalid("invalid relative game path"));
    }
    Ok(path)
}

fn directory(path: &Path) -> io::Result<()> {
    if fs::symlink_metadata(path)?.is_dir() {
        Ok(())
    } else {
        Err(invalid("expected a game directory"))
    }
}

fn installation_id() -> io::Result<String> {
    let mut random = [0; 16];
    File::open("/dev/urandom")?.read_exact(&mut random)?;
    Ok(format!("{:032x}", u128::from_be_bytes(random)))
}

fn rename_new(from: &Path, to: &Path) -> io::Result<()> {
    let from = CString::new(from.as_os_str().as_bytes())?;
    let to = CString::new(to.as_os_str().as_bytes())?;
    // SAFETY: both C strings live throughout the call and the kernel copies them.
    if unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            libc::AT_FDCWD,
            from.as_ptr(),
            libc::AT_FDCWD,
            to.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    } == -1
    {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

/// Create a new file in a private staging tree. The input is streamed with constant memory.
pub fn import_file(staging: &Path, relative: &Path, input: impl Read) -> io::Result<()> {
    copy_file(staging, relative, input, None)
}

fn copy_file(
    staging: &Path,
    relative: &Path,
    mut input: impl Read,
    expected_size: Option<u64>,
) -> io::Result<()> {
    let relative = relative_path(relative)?;
    directory(staging)?;
    let mut parent = staging.to_path_buf();
    for component in relative.parent().unwrap().components() {
        parent.push(component);
        match fs::create_dir(&parent) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::AlreadyExists => directory(&parent)?,
            Err(error) => return Err(error),
        }
    }

    let temporary = parent.join(format!(".copy-{}", installation_id()?));
    let mut output = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)?;
    let copied = match expected_size {
        Some(size) => io::copy(&mut input.take(size + 1), &mut output).and_then(|copied| {
            if copied == size {
                Ok(copied)
            } else {
                Err(invalid("game file size changed during import"))
            }
        }),
        None => io::copy(&mut input, &mut output),
    };
    let result = copied
        .and_then(|_| output.sync_data())
        .and_then(|_| rename_new(&temporary, &staging.join(relative)));
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

/// # Safety
///
/// Path pointers must be readable for their supplied lengths. The error buffer
/// must be nonempty and writable for its supplied length. The descriptor must stay open
/// during the call.
#[cfg(any(target_os = "android", test))]
#[unsafe(no_mangle)]
pub unsafe extern "C" fn buran_copy_game_file(
    staging: *const u8,
    staging_len: usize,
    relative: *const u8,
    relative_len: usize,
    fd: libc::c_int,
    expected_size: i64,
    error: *mut u8,
    error_len: usize,
) -> libc::c_int {
    use std::ffi::OsStr;
    use std::slice;

    let result = (|| {
        let expected_size = match expected_size {
            -1 => None,
            0.. => Some(expected_size as u64),
            _ => return Err(invalid("invalid expected game file size")),
        };
        if fd < 0 {
            return Err(invalid("invalid game source descriptor"));
        }
        // SAFETY: the C bridge supplies live, non-null buffers and keeps the
        // caller's descriptor open while Rust duplicates and reads from it.
        let staging = unsafe { slice::from_raw_parts(staging, staging_len) };
        let relative = unsafe { slice::from_raw_parts(relative, relative_len) };
        let descriptor = unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned()?;
        copy_file(
            Path::new(OsStr::from_bytes(staging)),
            Path::new(OsStr::from_bytes(relative)),
            File::from(descriptor),
            expected_size,
        )
    })();
    match result {
        Ok(()) => 0,
        Err(failure) => {
            // SAFETY: the bridge supplies a nonempty writable buffer.
            let buffer = unsafe { slice::from_raw_parts_mut(error, error_len) };
            let message = failure.to_string();
            let length = message.len().min(buffer.len() - 1);
            for (slot, byte) in buffer[..length].iter_mut().zip(message.bytes()) {
                *slot = if byte.is_ascii_graphic() || byte == b' ' {
                    byte
                } else {
                    b'?'
                };
            }
            buffer[length] = 0;
            -1
        }
    }
}

pub fn game_executable_bits(game: &Path, relative: &Path) -> io::Result<u8> {
    let relative = relative_path(relative)?;
    directory(game)?;
    let mut executable = game.to_path_buf();
    for component in relative.parent().unwrap().components() {
        executable.push(component);
        directory(&executable)?;
    }
    executable.push(relative.file_name().unwrap());
    if !fs::symlink_metadata(&executable)?.is_file() {
        return Err(invalid("expected a regular Windows executable file"));
    }
    Ok(crate::pe::Executable::read(&File::open(executable)?)?.bits)
}

pub fn game_executables(game: &Path) -> io::Result<Vec<PathBuf>> {
    fn open_directory(path: &Path) -> io::Result<File> {
        OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW)
            .open(path)
    }

    fn scan(directory: &File, relative: &mut PathBuf, found: &mut Vec<PathBuf>) -> io::Result<()> {
        // Resolve children through the opened parent even if its original path is replaced.
        for entry in fs::read_dir(format!("/proc/self/fd/{}", directory.as_raw_fd()))? {
            let entry = entry?;
            relative.push(entry.file_name());
            if relative_path(relative).is_ok() {
                let kind = entry.file_type()?;
                if kind.is_dir() {
                    scan(&open_directory(&entry.path())?, relative, found)?;
                } else if kind.is_file()
                    && relative
                        .as_os_str()
                        .as_bytes()
                        .last_chunk::<4>()
                        .is_some_and(|suffix| suffix.eq_ignore_ascii_case(b".exe"))
                {
                    found.push(relative.clone());
                }
            }
            relative.pop();
        }
        Ok(())
    }

    let mut found = Vec::new();
    scan(&open_directory(game)?, &mut PathBuf::new(), &mut found)?;
    // Match the Kotlin sort order for names whose UTF-8 and UTF-16 order differs.
    found.sort_by_cached_key(|path| {
        let name = path.to_str().unwrap();
        (
            path.components().count(),
            name.to_lowercase().encode_utf16().collect::<Vec<_>>(),
            name.encode_utf16().collect::<Vec<_>>(),
        )
    });
    Ok(found)
}

/// Publishes the executable record before exposing the complete staging tree.
pub fn register_game(games: &Path, staging: &Path, relative: &Path) -> io::Result<(String, u8)> {
    directory(games)?;
    directory(staging)?;
    let games = games.canonicalize()?;
    let staging = staging.canonicalize()?;
    if staging.parent() != Some(&games)
        || !staging
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.starts_with(".import-"))
    {
        return Err(invalid(
            "expected a private staging directory in the game library",
        ));
    }

    let bits = game_executable_bits(&staging, relative)?;
    let id = installation_id()?;
    let records = games.join(".library");
    match fs::create_dir(&records) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => directory(&records)?,
        Err(error) => return Err(error),
    }
    let record = format!("{bits}\n{}", relative.display());
    import_file(&records, Path::new(&id), record.as_bytes())?;
    if let Err(error) = rename_new(&staging, &games.join(&id)) {
        let _ = fs::remove_file(records.join(&id));
        return Err(error);
    }
    Ok((id, bits))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::fd::AsRawFd;
    use std::os::unix::net::UnixStream;

    #[test]
    fn exe_recovery_orders_utf8_names_and_skips_links() {
        use std::os::unix::fs::symlink;

        let temporary = std::env::temp_dir().join(installation_id().unwrap());
        let game = temporary.join("Игра с пробелами");
        fs::create_dir_all(game.join("Каталог")).unwrap();
        for filename in [
            "a.EXE",
            "A.exe",
            "Играть.ExE",
            "🦉.exe",
            "\u{e000}.EXE",
            "Каталог/second.exe",
            "Не EXE.txt",
            "Invalid\nname.exe",
        ] {
            File::create(game.join(filename)).unwrap();
        }
        symlink("a.EXE", game.join("link.exe")).unwrap();
        symlink("Каталог", game.join("link-dir")).unwrap();
        symlink(&game, temporary.join("linked-game")).unwrap();

        let expected = [
            "A.exe",
            "a.EXE",
            "Играть.ExE",
            "🦉.exe",
            "\u{e000}.EXE",
            "Каталог/second.exe",
        ]
        .map(PathBuf::from);
        assert_eq!(game_executables(&game).unwrap(), expected);
        assert!(game_executables(&temporary.join("linked-game")).is_err());
        fs::remove_dir_all(temporary).unwrap();
    }

    #[test]
    fn descriptor_copy_preserves_caller_ownership_and_checks_stream_size() {
        let staging = std::env::temp_dir().join(installation_id().unwrap());
        fs::create_dir(&staging).unwrap();
        let staging_bytes = staging.as_os_str().as_bytes();
        for (name, expected_size, success) in [
            (b"short".as_slice(), 5, false),
            (b"long".as_slice(), 3, false),
            (b"exact".as_slice(), 4, true),
            (b"unknown".as_slice(), -1, true),
        ] {
            let (source, mut producer) = UnixStream::pair().unwrap();
            producer.write_all(b"data").unwrap();
            drop(producer);
            let mut error = [0; 256];
            // SAFETY: the paths, error buffer and source live through this call.
            let status = unsafe {
                buran_copy_game_file(
                    staging_bytes.as_ptr(),
                    staging_bytes.len(),
                    name.as_ptr(),
                    name.len(),
                    source.as_raw_fd(),
                    expected_size,
                    error.as_mut_ptr(),
                    error.len(),
                )
            };
            assert_eq!(status == 0, success);
            assert!(source.take_error().unwrap().is_none());
            let file = staging.join(std::ffi::OsStr::from_bytes(name));
            if success {
                assert_eq!(fs::read(file).unwrap(), b"data");
            } else {
                assert!(!file.exists());
                assert!(error.starts_with(b"game file size changed"));
            }
        }
        assert_eq!(fs::read_dir(&staging).unwrap().count(), 2);
        fs::remove_dir_all(staging).unwrap();
    }
}
