use std::{env, ffi::OsString, fs, io, io::Write, path::Path, process::ExitCode};

mod component;
mod runtime_install;

fn probe() -> io::Result<()> {
    let checkpoint = Path::new("probe.count");
    let previous: u64 = match fs::read_to_string(checkpoint) {
        Ok(text) => text.trim().parse().map_err(io::Error::other)?,
        Err(e) if e.kind() == io::ErrorKind::NotFound => 0,
        Err(e) => return Err(e),
    };
    let next = previous
        .checked_add(1)
        .ok_or_else(|| io::Error::other("counter overflow"))?;
    fs::write("probe.count.tmp", next.to_string())?;
    fs::rename("probe.count.tmp", checkpoint)?;
    println!(
        "Проверка завершена №{next}\nАрхитектура: {}",
        env::consts::ARCH
    );
    Ok(())
}

fn execute(args: &[OsString]) -> io::Result<()> {
    match args {
        [op, dir] if op == "check" => {
            buran::launch(Path::new(dir), &env::current_exe()?, &["probe".into()])
        }
        [op, dir, backend, rest @ ..] if op == "run" => {
            buran::launch(Path::new(dir), Path::new(backend), rest)
        }
        [op, lock_dir, cwd, backend, rest @ ..] if op == "run-in" => buran::launch_in(
            Path::new(lock_dir),
            Path::new(cwd),
            Path::new(backend),
            rest,
        ),
        [op, staging, relative] if op == "import-file" => buran::game_import::import_file(
            Path::new(staging),
            Path::new(relative),
            io::stdin().lock(),
        ),
        [op, executable] if op == "game-requirements" => {
            if buran::game_requirements::needs_wine_mono(Path::new(executable))? {
                println!("wine-mono");
            }
            Ok(())
        }
        [op, game, executable] if op == "game-executable-bits" => {
            let bits =
                buran::game_import::game_executable_bits(Path::new(game), Path::new(executable))?;
            println!("{bits}");
            Ok(())
        }
        [op, kind, destination, stamp, extra @ ..] if op == "install-runtime" => {
            use runtime_install::Runtime;
            let invalid = || {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "invalid runtime install arguments",
                )
            };
            let number = |value: &OsString| {
                value
                    .to_str()
                    .and_then(|value| value.parse().ok())
                    .ok_or_else(invalid)
            };
            let runtime = match (kind.to_str(), extra) {
                (Some("gamescope"), []) => Runtime::Gamescope,
                (Some("box"), [uid, gid]) => Runtime::Box {
                    uid: number(uid)?,
                    gid: number(gid)?,
                },
                (Some("wine64"), []) => Runtime::Wine64,
                (Some("wine86"), []) => Runtime::Wine86,
                _ => return Err(invalid()),
            };
            runtime_install::install(
                runtime,
                Path::new(destination),
                stamp.to_str().ok_or_else(invalid)?,
                io::stdin().lock(),
            )
        }
        [op, game] if op == "game-executables" => {
            let paths = buran::game_import::game_executables(Path::new(game))?;
            let mut output = io::BufWriter::new(io::stdout().lock());
            for path in paths {
                writeln!(output, "{}", path.display())?;
            }
            output.flush()
        }
        [op, staged, destination, size, sha256] if op == "publish-component" => {
            let size = size
                .to_str()
                .and_then(|value| value.parse().ok())
                .ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidInput, "invalid component size")
                })?;
            component::publish(
                Path::new(staged),
                Path::new(destination),
                size,
                sha256.as_encoded_bytes(),
            )
        }
        [op, games, staging, executable] if op == "register-game" => {
            let (id, bits) = buran::game_import::register_game(
                Path::new(games),
                Path::new(staging),
                Path::new(executable),
            )?;
            println!("{id}\t{bits}");
            Ok(())
        }
        [op] if op == "probe" => probe(),
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "usage: buran check DIR | buran run DIR /absolute/backend [ARG ...] | buran run-in LOCK_DIR CWD /absolute/backend [ARG ...] | buran import-file STAGING RELATIVE_PATH | buran game-requirements EXE | buran game-executable-bits GAME RELATIVE_EXE | buran game-executables GAME | buran publish-component STAGED DESTINATION SIZE SHA256 | buran register-game GAMES STAGING RELATIVE_EXE",
        )),
    }
}

fn main() -> ExitCode {
    match execute(&env::args_os().skip(1).collect::<Vec<_>>()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("Buran: {e}");
            ExitCode::FAILURE
        }
    }
}
