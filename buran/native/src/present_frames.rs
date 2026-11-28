use std::collections::HashMap;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};

const HEADER: usize = 32;
const COMPLETION: usize = 40;
const MAX_PACKETS: usize = 256;
const MAX_READS: usize = 33;
const MIN_SAMPLE_US: u64 = 950_000;
const STALE_MS: u64 = 2_500;

struct Rate {
    event: u32,
    last_ust: u64,
    first_ust: u64,
    frames: u32,
    fps: Option<f32>,
    last_frame_ms: u64,
}

impl Rate {
    fn new(event: u32) -> Self {
        Self {
            event,
            last_ust: 0,
            first_ust: 0,
            frames: 0,
            fps: None,
            last_frame_ms: 0,
        }
    }

    fn record(&mut self, ust: u64, now_ms: u64) {
        // Several completions can describe the same displayed instant.
        if ust <= self.last_ust {
            return;
        }
        self.last_ust = ust;
        self.last_frame_ms = now_ms;
        if self.first_ust == 0 {
            self.first_ust = ust;
            return;
        }
        self.frames += 1;
        let interval_us = ust - self.first_ust;
        if interval_us >= MIN_SAMPLE_US {
            self.fps = Some(self.frames as f32 * 1_000_000.0 / interval_us as f32);
            self.first_ust = ust;
            self.frames = 0;
        }
    }
}

pub struct PresentFrames {
    socket: OwnedFd,
    opcode: u8,
    pending: Vec<u8>,
    rates: HashMap<u32, Rate>,
}

impl PresentFrames {
    fn packet_size(header: &[u8]) -> io::Result<usize> {
        if !matches!(header[0] & 0x7f, 1 | 35) {
            return Ok(HEADER);
        }
        let words = u32_at(header, 4);
        if words > 65_535 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid X11 packet size",
            ));
        }
        Ok(HEADER + words as usize * 4)
    }

    fn record(rates: &mut HashMap<u32, Rate>, opcode: u8, packet: &[u8], now_ms: u64) {
        // XGE PresentCompleteNotify for a pixmap that was actually displayed.
        if packet.len() != COMPLETION
            || packet[0] & 0x7f != 35
            || packet[1] != opcode
            || packet[8..10] != [1, 0]
            || packet[10] != 0
            || packet[11] == 2
        {
            return;
        }
        if let Some(rate) = rates.get_mut(&u32_at(packet, 16))
            && rate.event == u32_at(packet, 12)
        {
            let ust = u64::from_le_bytes(packet[24..32].try_into().unwrap());
            rate.record(ust, now_ms);
        }
    }

    fn drain(&mut self, now_ms: u64) -> io::Result<Option<f32>> {
        let mut offset = 0;
        let mut packet_count = 0;
        let mut reads = 0;
        let mut chunk = [0u8; 8192];
        while packet_count < MAX_PACKETS {
            let packet = &self.pending[offset..];
            if packet.len() >= HEADER {
                let size = Self::packet_size(packet)?;
                if packet.len() >= size {
                    Self::record(&mut self.rates, self.opcode, &packet[..size], now_ms);
                    offset += size;
                    packet_count += 1;
                    continue;
                }
            }
            if reads == MAX_READS {
                break;
            }
            reads += 1;
            // MSG_DONTWAIT affects only this receive, not the shared socket description.
            // SAFETY: this object owns the fd and chunk is writable to its length.
            let n = unsafe {
                libc::recv(
                    self.socket.as_raw_fd(),
                    chunk.as_mut_ptr().cast(),
                    chunk.len(),
                    libc::MSG_DONTWAIT,
                )
            };
            if n > 0 {
                self.pending.extend_from_slice(&chunk[..n as usize]);
            } else if n == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "X11 Present connection closed",
                ));
            } else {
                let error = io::Error::last_os_error();
                match error.kind() {
                    io::ErrorKind::WouldBlock => break,
                    io::ErrorKind::Interrupted => continue,
                    _ => return Err(error),
                }
            }
        }
        if offset != 0 {
            drop(self.pending.drain(..offset));
        }
        // Wine may use a child drawable. Never add parent and child rates together.
        Ok(self
            .rates
            .values()
            .filter_map(|rate| {
                (now_ms.saturating_sub(rate.last_frame_ms) <= STALE_MS)
                    .then_some(rate.fps)
                    .flatten()
            })
            .reduce(f32::max))
    }
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

/// The caller transfers ownership of a valid duplicated descriptor.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn buran_present_open(fd: RawFd, opcode: u8) -> *mut PresentFrames {
    // SAFETY: the JNI caller provides an independently owned fd.
    let socket = unsafe { OwnedFd::from_raw_fd(fd) };
    Box::into_raw(Box::new(PresentFrames {
        socket,
        opcode,
        pending: Vec::new(),
        rates: HashMap::new(),
    }))
}

/// The JNI owner serializes mutation and destruction of the opaque handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn buran_present_track(
    handle: *mut PresentFrames,
    window: u32,
    event: u32,
    active: bool,
) {
    // SAFETY: handle was returned by open and is exclusively held by this call.
    let frames = unsafe { &mut *handle };
    if active {
        frames.rates.insert(window, Rate::new(event));
    } else {
        frames.rates.remove(&window);
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn buran_present_read(handle: *mut PresentFrames, now_ms: u64) -> f32 {
    // SAFETY: same exclusive-handle contract as track.
    match unsafe { &mut *handle }.drain(now_ms) {
        Ok(fps) => fps.unwrap_or(f32::NAN),
        Err(_) => -1.0,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn buran_present_close(handle: *mut PresentFrames) {
    // SAFETY: the JNI owner restores box ownership exactly once after the last use.
    drop(unsafe { Box::from_raw(handle) });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use std::os::unix::net::UnixStream;

    fn completion(window: u32, event: u32, ust: u64, mode: u8) -> [u8; COMPLETION] {
        let mut packet = [0; COMPLETION];
        packet[0] = 35;
        packet[1] = 143;
        packet[4..8].copy_from_slice(&2u32.to_le_bytes());
        packet[8] = 1;
        packet[11] = mode;
        packet[12..16].copy_from_slice(&event.to_le_bytes());
        packet[16..20].copy_from_slice(&window.to_le_bytes());
        packet[24..32].copy_from_slice(&ust.to_le_bytes());
        packet
    }

    #[test]
    fn drains_split_packets_filters_non_displayed_frames_and_expires_rates() {
        let (socket, mut writer) = UnixStream::pair().unwrap();
        let mut frames = PresentFrames {
            socket: socket.into(),
            opcode: 143,
            pending: Vec::new(),
            rates: HashMap::from([(8, Rate::new(21)), (9, Rate::new(22))]),
        };
        let first = completion(8, 21, 1_000_000, 0);
        writer.write_all(&first[..36]).unwrap();
        assert_eq!(frames.drain(100).unwrap(), None);
        writer.write_all(&first[36..]).unwrap();
        writer.write_all(&[0; HEADER]).unwrap();
        for packet in [
            completion(8, 21, 1_000_000, 0),
            completion(8, 21, 1_200_000, 2),
            completion(8, 20, 1_300_000, 0),
            completion(8, 21, 1_500_000, 0),
            completion(8, 21, 2_000_000, 0),
            completion(9, 22, 1_000_000, 0),
            completion(9, 22, 2_000_000, 0),
        ] {
            writer.write_all(&packet).unwrap();
        }
        assert_eq!(frames.drain(200).unwrap(), Some(2.0));
        assert_eq!(frames.drain(2_701).unwrap(), None);
        drop(writer);
        assert_eq!(
            frames.drain(2_702).unwrap_err().kind(),
            io::ErrorKind::UnexpectedEof
        );
    }
}
