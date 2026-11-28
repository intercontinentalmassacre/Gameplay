use std::{fs::File, io, os::unix::fs::FileExt};

pub fn word(bytes: &[u8], offset: usize) -> Option<u16> {
    Some(u16::from_le_bytes(
        bytes.get(offset..)?.get(..2)?.try_into().ok()?,
    ))
}

pub fn dword(bytes: &[u8], offset: usize) -> Option<u32> {
    Some(u32::from_le_bytes(
        bytes.get(offset..)?.get(..4)?.try_into().ok()?,
    ))
}

pub struct Executable<'a> {
    pub bits: u8,
    file: &'a File,
    length: u64,
    optional_offset: u64,
    optional_size: u16,
    sections: u16,
}

impl<'a> Executable<'a> {
    pub fn read(file: &'a File) -> io::Result<Self> {
        let bad_pe = || {
            io::Error::new(
                io::ErrorKind::InvalidInput,
                "expected a Windows PE executable",
            )
        };
        let length = file.metadata()?.len();
        let mut dos = [0; 64];
        file.read_exact_at(&mut dos, 0).map_err(|_| bad_pe())?;
        let nt_offset = u64::from(dword(&dos, 60).ok_or_else(bad_pe)?);
        if &dos[..2] != b"MZ" || nt_offset < dos.len() as u64 {
            return Err(bad_pe());
        }
        let mut coff = [0; 24];
        file.read_exact_at(&mut coff, nt_offset)
            .map_err(|_| bad_pe())?;
        let characteristics = word(&coff, 22).ok_or_else(bad_pe)?;
        let sections = word(&coff, 6).ok_or_else(bad_pe)?;
        if &coff[..4] != b"PE\0\0"
            || characteristics & 2 == 0
            || characteristics & 0x2000 != 0
            || !(1..=96).contains(&sections)
        {
            return Err(bad_pe());
        }
        let optional_size = word(&coff, 20).ok_or_else(bad_pe)?;
        let optional_offset = nt_offset + coff.len() as u64;
        let header_end = optional_offset + u64::from(optional_size) + u64::from(sections) * 40;
        if header_end > length {
            return Err(bad_pe());
        }
        let mut magic = [0; 2];
        file.read_exact_at(&mut magic, optional_offset)
            .map_err(|_| bad_pe())?;
        let bits = match (word(&coff, 4), u16::from_le_bytes(magic)) {
            (Some(0x014c), 0x010b) if optional_size >= 96 => 32,
            (Some(0x8664), 0x020b) if optional_size >= 112 => 64,
            (Some(0x014c | 0x8664), _) => return Err(bad_pe()),
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "supported Windows executables are x86 and x86-64",
                ));
            }
        };
        Ok(Self {
            bits,
            file,
            length,
            optional_offset,
            optional_size,
            sections,
        })
    }

    pub fn directory(&self, index: u16) -> Option<(u32, u32)> {
        let directories = if self.bits == 32 { 96 } else { 112 };
        let offset = directories + index * 8;
        if offset + 8 > self.optional_size {
            return None;
        }
        let mut entry = [0; 8];
        self.file
            .read_exact_at(
                &mut entry[..4],
                self.optional_offset + u64::from(directories - 4),
            )
            .ok()?;
        if u32::from(index) >= dword(&entry, 0)? {
            return None;
        }
        self.file
            .read_exact_at(&mut entry, self.optional_offset + u64::from(offset))
            .ok()?;
        Some((dword(&entry, 0)?, dword(&entry, 4)?))
    }

    pub fn read_rva(&self, rva: u32, bytes: &mut [u8]) -> Option<()> {
        let mut mapping = None;
        let size = u32::try_from(bytes.len()).ok()?;
        let sections_offset = self.optional_offset + u64::from(self.optional_size);
        for number in 0..u64::from(self.sections) {
            let mut section = [0; 40];
            self.file
                .read_exact_at(&mut section, sections_offset + number * 40)
                .ok()?;
            let Some(delta) = rva.checked_sub(dword(&section, 12)?) else {
                continue;
            };
            let raw_size = dword(&section, 16)?;
            let virtual_size = dword(&section, 8)?;
            let mapped_size = if virtual_size == 0 {
                raw_size
            } else {
                raw_size.min(virtual_size)
            };
            if delta.checked_add(size)? > mapped_size {
                continue;
            }
            let raw_start = u64::from(dword(&section, 20)?);
            let offset = raw_start + u64::from(delta);
            if raw_start < sections_offset + u64::from(self.sections) * 40
                || offset + u64::from(size) > self.length
                || mapping.replace(offset).is_some()
            {
                return None;
            }
        }
        self.file.read_exact_at(bytes, mapping?).ok()
    }
}
