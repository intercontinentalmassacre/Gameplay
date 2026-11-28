use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::{self, Read},
    os::unix::fs::OpenOptionsExt,
    path::Path,
};

pub fn publish(
    staged: &Path,
    destination: &Path,
    size: u64,
    expected_digest: &[u8],
) -> io::Result<()> {
    if !staged.is_absolute()
        || !destination.is_absolute()
        || staged.parent() != destination.parent()
        || staged.file_name().is_none()
        || destination.file_name().is_none()
        || staged == destination
        || expected_digest.len() != 64
        || !expected_digest
            .iter()
            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected distinct sibling paths and a lowercase SHA-256 digest",
        ));
    }

    // The application writes its private staging file before invoking the runner.
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(staged)?;
    let metadata = file.metadata()?;
    if !metadata.is_file() || metadata.len() != size {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "unexpected component file size or type",
        ));
    }
    let mut digest = Sha256::new();
    let bytes = io::copy(&mut (&file).take(size.saturating_add(1)), &mut digest)?;
    if bytes != size || format!("{:x}", digest.finalize()).as_bytes() != expected_digest {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "component SHA-256 mismatch",
        ));
    }
    file.sync_all()?;
    fs::rename(staged, destination)
}
