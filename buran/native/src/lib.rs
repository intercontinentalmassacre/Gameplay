//! Start one foreground backend per environment on Android or Linux.
use std::ffi::OsString;
use std::fs::{self, File, OpenOptions};
use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::Command;

pub fn launch(directory: &Path, backend: &Path, arguments: &[OsString]) -> io::Result<()> {
    launch_in(directory, directory, backend, arguments)
}

pub fn launch_in(
    lock_directory: &Path,
    working_directory: &Path,
    backend: &Path,
    arguments: &[OsString],
) -> io::Result<()> {
    if !backend.is_absolute() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected an absolute backend path",
        ));
    }
    fs::create_dir_all(lock_directory)?;
    let lock = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(lock_directory.join(".buran.lock"))?;
    if let Err(error) = lock_file(&lock, true) {
        if error.kind() == io::ErrorKind::WouldBlock {
            return Err(io::Error::new(
                io::ErrorKind::WouldBlock,
                "environment is busy",
            ));
        }
        return Err(error);
    }
    // Keep the lock across exec. The kernel releases it when the backend closes
    // this descriptor or exits, including after a crash.
    // SAFETY: the file owns this descriptor throughout fcntl and exec.
    if unsafe { libc::fcntl(lock.as_raw_fd(), libc::F_SETFD, 0) } < 0 {
        return Err(io::Error::last_os_error());
    }
    Err(Command::new(backend)
        .args(arguments)
        .current_dir(working_directory)
        .exec())
}

pub fn lock_file(file: &File, nonblocking: bool) -> io::Result<()> {
    let operation = libc::LOCK_EX | if nonblocking { libc::LOCK_NB } else { 0 };
    loop {
        // SAFETY: the file owns the descriptor throughout flock.
        if unsafe { libc::flock(file.as_raw_fd(), operation) } == 0 {
            return Ok(());
        }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted {
            return Err(error);
        }
    }
}

pub mod game_import;
mod xvfb_pixels;

pub mod game_requirements;
mod pe;
mod present_frames;
