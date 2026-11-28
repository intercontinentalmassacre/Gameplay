//! Inspect PE/CLR metadata for dependencies that Buran can resolve.
use crate::pe::{Executable, dword, word};
use std::{fs::OpenOptions, io, os::unix::fs::OpenOptionsExt, path::Path};

const MAX_METADATA: u32 = 16 * 1024 * 1024;

pub fn needs_wine_mono(path: &Path) -> io::Result<bool> {
    if !path.is_absolute() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected an absolute executable path",
        ));
    }
    let file = OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)?;
    if !file.metadata()?.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected a regular executable",
        ));
    }
    match Executable::read(&file) {
        Ok(pe) => Ok(classic_clr(&pe).unwrap_or(false)),
        Err(error) if error.kind() == io::ErrorKind::InvalidInput => Ok(false),
        Err(error) => Err(error),
    }
}

fn classic_clr(pe: &Executable<'_>) -> Option<bool> {
    let (cli_rva, cli_size) = pe.directory(14)?;
    let mut cli = [0; 72];
    if cli_rva == 0 || cli_size != cli.len() as u32 {
        return None;
    }
    pe.read_rva(cli_rva, &mut cli)?;
    let flags = dword(&cli, 16)?;
    // ILONLY, no native entry point or managed native image.
    if dword(&cli, 0)? != cli_size
        || flags & 0x11 != 1
        || dword(&cli, 64)? != 0
        || dword(&cli, 68)? != 0
    {
        return None;
    }
    let entry = dword(&cli, 20)?;
    if entry >> 24 != 6 || entry & 0x00ff_ffff == 0 {
        return None;
    }

    let metadata_rva = dword(&cli, 8)?;
    let metadata_size = dword(&cli, 12)?;
    if metadata_rva == 0 || !(20..=MAX_METADATA).contains(&metadata_size) {
        return None;
    }
    let mut metadata = vec![0; metadata_size as usize];
    pe.read_rva(metadata_rva, &mut metadata)?;
    let (tables, strings, blobs) = metadata_streams(&metadata)?;
    assembly_references(tables, strings, blobs, (entry & 0x00ff_ffff) as usize)
}

// ECMA-335 II.24.2: CLR metadata root and stream directory.
fn metadata_streams(metadata: &[u8]) -> Option<(&[u8], &[u8], &[u8])> {
    if metadata.get(..4)? != b"BSJB" {
        return None;
    }
    let version_length = dword(metadata, 12)? as usize;
    let version_end = 16usize.checked_add(version_length)?;
    if version_length == 0
        || !version_length.is_multiple_of(4)
        || !metadata.get(16..version_end)?.contains(&0)
    {
        return None;
    }
    let count = word(metadata, version_end + 2)?;
    if !(3..=32).contains(&count) {
        return None;
    }
    let mut cursor = version_end + 4;
    let mut ranges: Vec<std::ops::Range<usize>> = Vec::with_capacity(count as usize);
    let (mut tables, mut strings, mut blobs) = (None, None, None);
    for _ in 0..count {
        let start = dword(metadata, cursor)? as usize;
        let size = dword(metadata, cursor + 4)? as usize;
        let end = start.checked_add(size)?;
        cursor += 8;
        let remaining = metadata.get(cursor..)?;
        let name_length = remaining.iter().take(32).position(|&byte| byte == 0)?;
        let name = &remaining[..name_length];
        cursor += (name_length + 1).next_multiple_of(4);
        let range = start..end;
        if ranges
            .iter()
            .any(|prior| prior.start < range.end && range.start < prior.end)
        {
            return None;
        }
        let stream = metadata.get(range.clone())?;
        ranges.push(range);
        let slot = match name {
            b"#~" | b"#-" => &mut tables,
            b"#Strings" => &mut strings,
            b"#Blob" => &mut blobs,
            _ => continue,
        };
        if slot.replace(stream).is_some() {
            return None;
        }
    }
    if ranges.iter().any(|range| range.start < cursor)
        || strings?.first()? != &0
        || blobs?.first()? != &0
    {
        return None;
    }
    Some((tables?, strings?, blobs?))
}

fn assembly_references(
    tables: &[u8],
    strings: &[u8],
    blobs: &[u8],
    entry_method: usize,
) -> Option<bool> {
    const ASSEMBLY: usize = 32;
    const ASSEMBLY_REF: usize = 35;
    let flags = *tables.get(6)?;
    let valid = u64::from_le_bytes(tables.get(8..16)?.try_into().ok()?);
    if valid >> 45 != 0 {
        return None;
    }
    let mut cursor = 24;
    let mut rows = [0; 45];
    for (table, rows) in rows.iter_mut().enumerate() {
        if valid & (1 << table) != 0 {
            *rows = dword(tables, cursor)? as usize;
            cursor += 4;
        }
    }
    if rows[0] != 1 || rows[6] < entry_method || rows[ASSEMBLY] != 1 || rows[ASSEMBLY_REF] == 0 {
        return None;
    }
    if flags & 0x40 != 0 {
        // Extra data following the row counts, used by edit-and-continue metadata.
        cursor += 4;
    }
    let widths = IndexWidths { rows, flags };
    let mut references = None;
    for (table, &rows) in widths
        .rows
        .iter()
        .enumerate()
        .filter(|(_, rows)| **rows > 0)
    {
        let size = widths.row_size(table);
        let end = cursor.checked_add(rows.checked_mul(size)?)?;
        let data = tables.get(cursor..end)?;
        if table == ASSEMBLY_REF {
            references = Some(data.chunks_exact(size));
        }
        cursor = end;
    }

    let mut classic = false;
    for row in references? {
        let major = word(row, 0)?;
        let name_index = heap_index(row, 12 + widths.blob(), widths.string())?;
        let remaining = strings.get(name_index..)?;
        // None of the recognized AssemblyRefs can have a longer name.
        let Some(length) = remaining.iter().take(32).position(|&byte| byte == 0) else {
            continue;
        };
        match &remaining[..length] {
            b"System.Private.CoreLib" => return Some(false),
            b"System.Runtime" if major >= 5 => return Some(false),
            b"mscorlib" if matches!(major, 1 | 2 | 4) => {
                let token = heap_index(row, 12, widths.blob())?;
                // The CLR Framework core library AssemblyRef carries this public-key token.
                let key_token = b"\x08\xb7\x7a\x5c\x56\x19\x34\xe0\x89";
                classic |= dword(row, 8)? & 1 == 0
                    && token != 0
                    && blobs.get(token..)?.starts_with(key_token);
            }
            _ => {}
        }
    }
    Some(classic)
}

fn heap_index(row: &[u8], offset: usize, width: usize) -> Option<usize> {
    if width == 2 {
        Some(word(row, offset)? as usize)
    } else {
        Some(dword(row, offset)? as usize)
    }
}

struct IndexWidths {
    rows: [usize; 45],
    flags: u8,
}

impl IndexWidths {
    fn heap(&self, flag: u8) -> usize {
        if self.flags & flag == 0 { 2 } else { 4 }
    }
    fn string(&self) -> usize {
        self.heap(1)
    }
    fn blob(&self) -> usize {
        self.heap(4)
    }

    fn table(&self, table: usize) -> usize {
        if self.rows[table] < 65536 { 2 } else { 4 }
    }

    fn coded(&self, tag_bits: u32, tables: &[usize]) -> usize {
        if tables
            .iter()
            .any(|&table| self.rows[table] >= (1 << (16 - tag_bits)))
        {
            4
        } else {
            2
        }
    }

    // ECMA-335 II.22 and II.24.2.6. Widths depend on target table row counts and heap flags.
    fn row_size(&self, table: usize) -> usize {
        let (s, g, b) = (self.string(), self.heap(2), self.blob());
        let t = |target| self.table(target);
        let list = |table, pointer| {
            if self.rows[pointer] == 0 {
                t(table)
            } else {
                t(pointer)
            }
        };
        let c = |bits, tables: &[usize]| self.coded(bits, tables);
        let type_ref = c(2, &[2, 1, 27]);
        let method_ref = c(1, &[6, 10]);
        let implementation = c(2, &[38, 35, 39]);
        match table {
            0 => 2 + s + 3 * g,
            1 => c(2, &[0, 26, 35, 1]) + 2 * s,
            2 => 4 + 2 * s + type_ref + list(4, 3) + list(6, 5),
            3 => t(4),
            4 => 2 + s + b,
            5 => t(6),
            6 => 8 + s + b + list(8, 7),
            7 => t(8),
            8 => 4 + s,
            9 => t(2) + type_ref,
            10 => c(3, &[2, 1, 26, 6, 27]) + s + b,
            11 => 2 + c(2, &[4, 8, 23]) + b,
            12 => {
                c(
                    5,
                    &[
                        6, 4, 1, 2, 8, 9, 10, 0, 14, 23, 20, 17, 26, 27, 32, 35, 38, 39, 40, 42,
                        44, 43,
                    ],
                ) + c(3, &[6, 10])
                    + b
            }
            13 => c(1, &[4, 8]) + b,
            14 => 2 + c(2, &[2, 6, 32]) + b,
            15 => 6 + t(2),
            16 => 4 + t(4),
            17 => b,
            18 => t(2) + list(20, 19),
            19 => t(20),
            20 => 2 + s + type_ref,
            21 => t(2) + list(23, 22),
            22 => t(23),
            23 => 2 + s + b,
            24 => 2 + t(6) + c(1, &[20, 23]),
            25 => t(2) + 2 * method_ref,
            26 => s,
            27 => b,
            28 => 2 + c(1, &[4, 6]) + s + t(26),
            29 => 4 + t(4),
            30 => 8,
            31 => 4,
            32 => 16 + b + 2 * s,
            33 => 4,
            34 => 12,
            35 => 12 + 2 * b + 2 * s,
            36 => 4 + t(35),
            37 => 12 + t(35),
            38 => 4 + s + b,
            39 => 8 + 2 * s + implementation,
            40 => 8 + s + implementation,
            41 => 2 * t(2),
            42 => 4 + c(1, &[2, 6]) + s,
            43 => method_ref + b,
            44 => t(42) + type_ref,
            _ => unreachable!("only ECMA-335 tables 0 through 44 are supported"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modern_runtime_reference_excludes_wine_mono() {
        let valid = (1u64 << 0) | (1 << 6) | (1 << 32) | (1 << 35);
        let mut tables = vec![0; 24];
        tables[8..16].copy_from_slice(&valid.to_le_bytes());
        for count in [1u32, 1, 1, 2] {
            tables.extend_from_slice(&count.to_le_bytes());
        }
        // Module, MethodDef and Assembly row data, followed by two AssemblyRefs.
        tables.resize(tables.len() + 10 + 14 + 22, 0);
        let mut first = [0; 20];
        first[0] = 4;
        first[12] = 1;
        first[14] = 1;
        tables.extend_from_slice(&first);
        let mut second = [0; 20];
        second[0] = 8;
        second[14] = 10;
        tables.extend_from_slice(&second);
        let blobs = b"\0\x08\xb7\x7a\x5c\x56\x19\x34\xe0\x89";
        assert_eq!(
            assembly_references(&tables, b"\0mscorlib\0System.Runtime\0", blobs, 1),
            Some(false)
        );
        assert_eq!(
            assembly_references(&tables, b"\0mscorlib\0System.Private.CoreLib\0", blobs, 1),
            Some(false)
        );
    }
}
