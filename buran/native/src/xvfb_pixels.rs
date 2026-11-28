fn copy_frame(
    source: &[u8],
    source_stride: usize,
    destination: &mut [u8],
    destination_stride: usize,
    width: usize,
    height: usize,
    big_endian: bool,
) -> bool {
    let Some(row_bytes) = width.checked_mul(4) else {
        return false;
    };
    if row_bytes == 0
        || height == 0
        || source_stride < row_bytes
        || destination_stride < row_bytes
        || source_stride
            .checked_mul(height)
            .is_none_or(|size| size > source.len())
        || destination_stride
            .checked_mul(height)
            .is_none_or(|size| size > destination.len())
    {
        return false;
    }

    for (input, output) in source
        .chunks_exact(source_stride)
        .zip(destination.chunks_exact_mut(destination_stride))
        .take(height)
    {
        let (input, _) = input[..row_bytes].as_chunks::<4>();
        let (output, _) = output[..row_bytes].as_chunks_mut::<4>();
        // XWD stores 0x00RRGGBB. Android's locked bitmap expects R, G, B, A bytes.
        if big_endian {
            for (&pixel, rgba) in input.iter().zip(output) {
                *rgba = (u32::from_le_bytes(pixel) >> 8 | 0xff00_0000).to_le_bytes();
            }
        } else {
            for (&pixel, rgba) in input.iter().zip(output) {
                *rgba = (u32::from_be_bytes(pixel) >> 8 | 0xff00_0000).to_le_bytes();
            }
        }
    }
    true
}

/// Copies an XWD framebuffer into an opaque Android RGBA bitmap.
///
/// # Safety
///
/// Both pointers must describe valid, disjoint buffers of the indicated lengths.
/// Only the caller may write the destination during this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn buran_xvfb_copy(
    source: *const u8,
    source_size: usize,
    source_stride: usize,
    destination: *mut u8,
    destination_size: usize,
    destination_stride: usize,
    width: usize,
    height: usize,
    big_endian: bool,
) -> bool {
    if source.is_null()
        || destination.is_null()
        || source_size > isize::MAX as usize
        || destination_size > isize::MAX as usize
    {
        return false;
    }
    // The JNI bridge supplies the mapped source and the locked bitmap.
    let source = unsafe { std::slice::from_raw_parts(source, source_size) };
    let destination = unsafe { std::slice::from_raw_parts_mut(destination, destination_size) };
    copy_frame(
        source,
        source_stride,
        destination,
        destination_stride,
        width,
        height,
        big_endian,
    )
}

#[cfg(test)]
mod tests {
    use super::copy_frame;

    #[test]
    fn converts_colors_and_preserves_padding_from_unaligned_xwd_rows() {
        let expected = [
            0x12, 0x34, 0x56, 0xff, 0x98, 0x76, 0x54, 0xff, 0xee, 0xee, 0xab, 0xcd, 0xef, 0xff,
            0x01, 0x23, 0x45, 0xff, 0xee, 0xee,
        ];
        let little = [
            0, 0x56, 0x34, 0x12, 0, 0x54, 0x76, 0x98, 0, 0xee, 0xef, 0xcd, 0xab, 0, 0x45, 0x23,
            0x01, 0, 0xee,
        ];
        let big = [
            0, 0, 0x12, 0x34, 0x56, 0, 0x98, 0x76, 0x54, 0xee, 0, 0xab, 0xcd, 0xef, 0, 0x01, 0x23,
            0x45, 0xee,
        ];
        for (source, big_endian) in [(little, false), (big, true)] {
            let mut destination = [0xee; 20];
            assert!(copy_frame(
                &source[1..],
                9,
                &mut destination,
                10,
                2,
                2,
                big_endian
            ));
            assert_eq!(destination, expected);
            assert!(!copy_frame(
                &source[1..17],
                9,
                &mut destination,
                10,
                2,
                2,
                big_endian
            ));
            assert_eq!(destination, expected);
        }
    }
}
