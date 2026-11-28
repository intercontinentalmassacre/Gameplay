use sha2::{Digest, Sha256};
use std::collections::VecDeque;
use std::ffi::{CString, OsStr, OsString};
use std::fs::{self, OpenOptions};
use std::io::{self, BufReader, Read};
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Component, Path, PathBuf};

const GAMESCOPE_VERSION: &str = "bannerlator-linuxfs-r9+gamescope-3.16.29-p4";

pub enum Runtime {
    Gamescope,
    Box { uid: u32, gid: u32 },
    Wine64,
    Wine86,
}

impl Runtime {
    fn valid(&self, root: &Path) -> bool {
        match self {
            Self::Gamescope => {
                fs::read_to_string(root.join(".buran-linuxfs"))
                    .is_ok_and(|text| text.trim() == GAMESCOPE_VERSION)
                    && [
                        "usr/local/bin/gamescope",
                        "usr/bin/Xwayland",
                        "usr/bin/gamescopereaper",
                    ]
                    .iter()
                    .all(|path| executable(&root.join(path)))
            }
            Self::Box { .. } => executable(&root.join("bin/busybox")),
            Self::Wine64 => executable(&root.join("bin/wine64")),
            Self::Wine86 => {
                executable(&root.join("bin/wine")) && executable(&root.join("bin/wineserver.i386"))
            }
        }
    }

    fn stamp(&self) -> &'static str {
        match self {
            Self::Gamescope => ".buran-installed-sha256",
            _ => ".version",
        }
    }

    fn extract(&self, source: impl Read, root: &Path, stamp: &str) -> io::Result<()> {
        match self {
            Self::Gamescope | Self::Box { .. } => {
                let mut source = HashReader {
                    inner: BufReader::new(source),
                    hash: Sha256::new(),
                };
                {
                    let mut decoder = zstd::stream::read::Decoder::new(&mut source)?;
                    decoder.window_log_max(27)?;
                    unpack(&mut decoder, root, false)?;
                    io::copy(&mut decoder, &mut io::sink())?;
                }
                if matches!(self, Self::Gamescope)
                    && format!("{:x}", source.hash.finalize()) != stamp
                {
                    return Err(invalid("Gamescope archive checksum mismatch"));
                }
            }
            Self::Wine64 | Self::Wine86 => {
                let mut decoder = xz2::read::XzDecoder::new(BufReader::new(source));
                unpack(&mut decoder, root, true)?;
                io::copy(&mut decoder, &mut io::sink())?;
            }
        }
        match self {
            Self::Box { uid, gid } => {
                fs::write(
                    root.join("etc/passwd"),
                    format!("buran:x:{uid}:{gid}:Buran:/home/buran:/bin/sh\n"),
                )?;
                fs::write(root.join("etc/group"), format!("buran:x:{gid}:\n"))?;
            }
            Self::Wine86 => {
                let server = root.join("bin/wineserver");
                fs::rename(&server, root.join("bin/wineserver.i386"))?;
                fs::write(&server, b"#!/bin/sh\nexec /usr/local/bin/box86 /opt/buran/wine86/bin/wineserver.i386 \"$@\"\n")?;
                fs::set_permissions(&server, fs::Permissions::from_mode(0o700))?;
            }
            _ => {}
        }
        Ok(())
    }
}

pub fn install(
    runtime: Runtime,
    destination: &Path,
    stamp: &str,
    mut source: impl Read,
) -> io::Result<()> {
    let parent = destination
        .parent()
        .ok_or_else(|| invalid("runtime destination has no parent"))?;
    let name = destination
        .file_name()
        .ok_or_else(|| invalid("runtime destination has no name"))?;
    if !destination.is_absolute()
        || stamp.is_empty()
        || stamp.len() > 128
        || !stamp.bytes().all(|byte| byte.is_ascii_hexdigit())
        || (matches!(runtime, Runtime::Gamescope) && stamp.len() != 64)
    {
        return Err(invalid("invalid runtime destination or stamp"));
    }
    fs::create_dir_all(parent)?;
    let lease = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .open(sidecar(parent, name, "lock"))?;
    buran::lock_file(&lease, false)?;
    if runtime.valid(destination)
        && fs::read_to_string(destination.join(runtime.stamp()))
            .is_ok_and(|value| value.trim() == stamp)
    {
        // The caller may already be feeding its pipe when a concurrent installer wins.
        io::copy(&mut source, &mut io::sink())?;
        return Ok(());
    }
    let staging = sidecar(parent, name, "pending");
    remove_tree(&staging)?;
    fs::create_dir(&staging)?;
    let result = (|| {
        runtime.extract(source, &staging, stamp)?;
        if !runtime.valid(&staging) {
            return Err(invalid(
                "runtime archive is missing its launcher or version marker",
            ));
        }
        fs::write(staging.join(runtime.stamp()), stamp)?;
        publish(&staging, destination)
    })();
    let _ = remove_tree(&staging);
    result
}

fn sidecar(parent: &Path, name: &OsStr, suffix: &str) -> PathBuf {
    let mut value = std::ffi::OsString::from(".buran-");
    value.push(name);
    value.push("-");
    value.push(suffix);
    parent.join(value)
}

fn remove_tree(path: &Path) -> io::Result<()> {
    match fs::remove_dir_all(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}

fn publish(staged: &Path, destination: &Path) -> io::Result<()> {
    if !destination.exists() {
        return fs::rename(staged, destination);
    }
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        let staged = CString::new(staged.as_os_str().as_encoded_bytes())?;
        let destination = CString::new(destination.as_os_str().as_encoded_bytes())?;
        // SAFETY: both paths are valid NUL-terminated strings owned for the syscall's duration.
        let status = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                libc::AT_FDCWD,
                staged.as_ptr(),
                libc::AT_FDCWD,
                destination.as_ptr(),
                libc::RENAME_EXCHANGE,
            )
        };
        if status != 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "atomic runtime replacement requires Linux",
    ))
}

fn executable(path: &Path) -> bool {
    fs::metadata(path)
        .is_ok_and(|metadata| metadata.is_file() && metadata.permissions().mode() & 0o100 != 0)
}

fn unpack(input: impl Read, root: &Path, strip: bool) -> io::Result<()> {
    let mut archive = tar::Archive::new(input);
    let mut directory_modes = Vec::new();
    let mut links = Vec::new();
    let mut total = 0_u64;
    let mut archive_prefix = None;
    for (index, entry) in archive.entries()?.enumerate() {
        if index >= 100_000 {
            return Err(invalid("too many runtime archive entries"));
        }
        let mut entry = entry?;
        let path = relative(&entry.path()?)?;
        let kind = entry.header().entry_type();
        let mode = entry.header().mode()? & 0o777;
        let path = if strip {
            let Some(prefix) = path.components().next() else {
                continue;
            };
            if archive_prefix.get_or_insert_with(|| prefix.as_os_str().to_owned())
                != prefix.as_os_str()
            {
                return Err(invalid("unexpected second Wine archive root"));
            }
            path.components().skip(1).collect()
        } else {
            path
        };
        if path.as_os_str().is_empty() {
            if !kind.is_dir() {
                return Err(invalid("non-directory runtime archive root"));
            }
            continue;
        }
        let parent = path.parent().unwrap_or_else(|| Path::new(""));
        ensure_directories(root, parent)?;
        let target = root.join(&path);
        if kind.is_dir() {
            ensure_directories(root, &path)?;
            directory_modes.push((target, mode));
        } else if kind.is_file() {
            total = total
                .checked_add(entry.size())
                .filter(|size| *size <= 8 * 1024 * 1024 * 1024)
                .ok_or_else(|| invalid("runtime archive exceeds unpacked size limit"))?;
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(target)?;
            if io::copy(&mut entry, &mut file)? != entry.size() {
                return Err(invalid("truncated runtime archive entry"));
            }
            file.set_permissions(fs::Permissions::from_mode(mode))?;
        } else if kind.is_symlink() {
            let link = entry
                .link_name()?
                .ok_or_else(|| invalid("runtime link without target"))?;
            safe_link(parent, &link)?;
            symlink(link, target)?;
            links.push(path);
        } else {
            return Err(invalid("unsupported entry in runtime archive"));
        }
    }
    for path in links {
        resolve_link_inside(root, &path)?;
    }
    directory_modes
        .sort_unstable_by_key(|(directory, _)| std::cmp::Reverse(directory.components().count()));
    for (directory, mode) in directory_modes {
        fs::set_permissions(directory, fs::Permissions::from_mode(mode))?;
    }
    Ok(())
}

fn relative(path: &Path) -> io::Result<PathBuf> {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::Normal(name) => result.push(name),
            _ => return Err(invalid("runtime archive path escapes its root")),
        }
    }
    Ok(result)
}

fn safe_link(parent: &Path, link: &Path) -> io::Result<()> {
    let mut depth = parent.components().count();
    for component in link.components() {
        match component {
            Component::CurDir => {}
            Component::Normal(_) => depth += 1,
            Component::ParentDir if depth > 0 => depth -= 1,
            _ => return Err(invalid("runtime archive link escapes its root")),
        }
    }
    Ok(())
}

fn resolve_link_inside(root: &Path, path: &Path) -> io::Result<()> {
    fn parts(path: &Path) -> io::Result<VecDeque<OsString>> {
        path.components()
            .filter(|part| *part != Component::CurDir)
            .map(|part| match part {
                Component::Normal(_) | Component::ParentDir => Ok(part.as_os_str().to_owned()),
                _ => Err(invalid("an absolute runtime symlink cannot be installed")),
            })
            .collect()
    }
    let mut pending = parts(path)?;
    let mut relative = PathBuf::new();
    let mut hops = 0;
    while let Some(part) = pending.pop_front() {
        if part == ".." {
            if !relative.pop() {
                return Err(invalid("runtime symlink chain escapes its root"));
            }
            continue;
        }
        relative.push(part);
        match fs::read_link(root.join(&relative)) {
            Ok(target) => {
                hops += 1;
                if hops > 40 {
                    return Err(invalid("runtime symlink chain is too long or cyclic"));
                }
                relative.pop();
                let mut target = parts(&target)?;
                target.append(&mut pending);
                pending = target;
            }
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::InvalidInput
                        | io::ErrorKind::NotFound
                        | io::ErrorKind::NotADirectory
                ) => {}
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

fn ensure_directories(root: &Path, path: &Path) -> io::Result<()> {
    let mut current = root.to_owned();
    for component in path.components() {
        current.push(component);
        match fs::symlink_metadata(&current) {
            Ok(metadata) if metadata.is_dir() => {}
            Ok(_) => return Err(invalid("runtime archive uses a non-directory parent")),
            Err(error) if error.kind() == io::ErrorKind::NotFound => fs::create_dir(&current)?,
            Err(error) => return Err(error),
        }
    }
    Ok(())
}

struct HashReader<R> {
    inner: R,
    hash: Sha256,
}

impl<R: Read> Read for HashReader<R> {
    fn read(&mut self, data: &mut [u8]) -> io::Result<usize> {
        let count = self.inner.read(data)?;
        self.hash.update(&data[..count]);
        Ok(count)
    }
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_entries_and_bounds_relative_links() {
        assert_eq!(
            relative(Path::new("./usr/lib")).unwrap(),
            Path::new("usr/lib")
        );
        assert!(relative(Path::new("../lib")).is_err());
        assert!(relative(Path::new("/usr/lib")).is_err());
        assert!(safe_link(Path::new("usr/lib"), Path::new("../local/lib")).is_ok());
        assert!(safe_link(Path::new("usr/lib"), Path::new("../../../outside")).is_err());
    }
}
