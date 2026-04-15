#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct Rect {
    pub left: u32,
    pub top: u32,
    pub right: u32,
    pub bottom: u32,
}

impl Rect {
    pub fn width(&self) -> u32 {
        self.right.saturating_sub(self.left)
    }

    pub fn height(&self) -> u32 {
        self.bottom.saturating_sub(self.top)
    }

    pub fn center_y(&self) -> u32 {
        (self.top + self.bottom) / 2
    }

    pub fn is_empty(&self) -> bool {
        self.left >= self.right || self.top >= self.bottom
    }

    pub fn union(&mut self, other: Self) {
        if self.is_empty() {
            *self = other;
            return;
        }

        self.left = self.left.min(other.left);
        self.top = self.top.min(other.top);
        self.right = self.right.max(other.right);
        self.bottom = self.bottom.max(other.bottom);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum ReadingOrder {
    #[default]
    LeftToRight,
    TopToBottomLeftToRight,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DetectedWord {
    pub text: String,
    pub confidence: f32,
    pub bounding_box: Rect,
    pub is_at_beginning_of_para: bool,
    pub end_para: bool,
    pub end_line: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextLine {
    pub text: String,
    pub bounding_box: Rect,
    pub word_rects: Vec<Rect>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TextBlock {
    pub lines: Vec<TextLine>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct OverlayColors {
    pub background_argb: u32,
    pub foreground_argb: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct PreparedTextLine {
    pub text: String,
    pub bounding_box: Rect,
    pub word_rects: Vec<Rect>,
    pub background_argb: u32,
    pub foreground_argb: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct PreparedTextBlock {
    pub source_text: String,
    pub translated_text: String,
    pub bounding_box: Rect,
    pub lines: Vec<PreparedTextLine>,
    pub background_argb: u32,
    pub foreground_argb: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct PreparedImageOverlay {
    pub rgba_bytes: Vec<u8>,
    pub width: u32,
    pub height: u32,
    pub extracted_text: String,
    pub translated_text: String,
    pub blocks: Vec<PreparedTextBlock>,
}

#[derive(Debug, Clone, PartialEq)]
struct WordInfo {
    text: String,
    confidence: f32,
    bounding_box: Rect,
    ghost_bbox: Option<Rect>,
    is_first_in_line: bool,
    is_last_in_line: bool,
    is_last_in_para: bool,
}

impl TextBlock {
    pub fn source_text(&self) -> String {
        self.lines
            .iter()
            .map(|line| line.text.as_str())
            .collect::<Vec<_>>()
            .join("\n")
    }

    pub fn translation_text(&self) -> String {
        self.lines
            .iter()
            .map(|line| line.text.trim())
            .filter(|line| !line.is_empty())
            .collect::<Vec<_>>()
            .join(" ")
    }

    pub fn bounds(&self) -> Rect {
        let Some(first) = self.lines.first().map(|line| line.bounding_box) else {
            return Rect::default();
        };
        let mut combined = first;
        for line in self.lines.iter().skip(1) {
            combined.union(line.bounding_box);
        }
        combined
    }
}

struct RasterImage<'a> {
    width: u32,
    height: u32,
    rgba: &'a [u8],
}

struct RasterImageMut {
    width: u32,
    height: u32,
    rgba: Vec<u8>,
}

impl<'a> RasterImage<'a> {
    fn new(rgba: &'a [u8], width: u32, height: u32) -> Result<Self, String> {
        let expected_len = width
            .checked_mul(height)
            .and_then(|pixels| pixels.checked_mul(4))
            .ok_or_else(|| "image dimensions overflow".to_string())? as usize;
        if rgba.len() != expected_len {
            return Err(format!(
                "invalid rgba size: expected {expected_len}, got {}",
                rgba.len()
            ));
        }
        Ok(Self {
            width,
            height,
            rgba,
        })
    }

    fn pixel_argb(&self, x: u32, y: u32) -> u32 {
        let index = ((y * self.width + x) * 4) as usize;
        u32::from_ne_bytes([
            self.rgba[index],
            self.rgba[index + 1],
            self.rgba[index + 2],
            self.rgba[index + 3],
        ])
    }
}

impl RasterImageMut {
    fn new(rgba: &[u8], width: u32, height: u32) -> Result<Self, String> {
        let image = RasterImage::new(rgba, width, height)?;
        Ok(Self {
            width: image.width,
            height: image.height,
            rgba: rgba.to_vec(),
        })
    }

    fn as_image(&self) -> RasterImage<'_> {
        RasterImage {
            width: self.width,
            height: self.height,
            rgba: &self.rgba,
        }
    }

    fn fill_rect(&mut self, rect: Rect, argb: u32) {
        let Some(rect) = clamp_rect(rect, self.width, self.height) else {
            return;
        };
        let bytes = argb.to_ne_bytes();
        for y in rect.top..rect.bottom {
            for x in rect.left..rect.right {
                let index = ((y * self.width + x) * 4) as usize;
                self.rgba[index..index + 4].copy_from_slice(&bytes);
            }
        }
    }
}

fn channel_r(color: u32) -> u8 {
    ((color >> 16) & 0xFF) as u8
}

fn channel_g(color: u32) -> u8 {
    ((color >> 8) & 0xFF) as u8
}

fn channel_b(color: u32) -> u8 {
    (color & 0xFF) as u8
}

fn argb(r: u8, g: u8, b: u8) -> u32 {
    0xFF00_0000 | ((r as u32) << 16) | ((g as u32) << 8) | b as u32
}

fn quantized_rgb(color: u32) -> u32 {
    argb(channel_r(color) & 0xF0, channel_g(color) & 0xF0, channel_b(color) & 0xF0)
}

fn clamp_rect(rect: Rect, width: u32, height: u32) -> Option<Rect> {
    if width == 0 || height == 0 {
        return None;
    }
    let left = rect.left.min(width - 1);
    let top = rect.top.min(height - 1);
    let right = rect.right.clamp(left + 1, width);
    let bottom = rect.bottom.clamp(top + 1, height);
    let clamped = Rect {
        left,
        top,
        right,
        bottom,
    };
    if clamped.is_empty() {
        None
    } else {
        Some(clamped)
    }
}

fn sample_dominant_color(image: &RasterImage<'_>, bounds: Rect) -> u32 {
    let Some(bounds) = clamp_rect(bounds, image.width, image.height) else {
        return argb(255, 255, 255);
    };
    let area = bounds.width() * bounds.height();
    if area == 0 {
        return argb(255, 255, 255);
    }

    #[derive(Default)]
    struct Bucket {
        count: u32,
        r_sum: u64,
        g_sum: u64,
        b_sum: u64,
    }

    let step = (area as usize / 500).max(1);
    let mut buckets = std::collections::HashMap::<u32, Bucket>::new();
    let mut seen = 0usize;
    for y in bounds.top..bounds.bottom {
        for x in bounds.left..bounds.right {
            if seen % step != 0 {
                seen += 1;
                continue;
            }
            let pixel = image.pixel_argb(x, y);
            let key = quantized_rgb(pixel);
            let bucket = buckets.entry(key).or_default();
            bucket.count += 1;
            bucket.r_sum += channel_r(pixel) as u64;
            bucket.g_sum += channel_g(pixel) as u64;
            bucket.b_sum += channel_b(pixel) as u64;
            seen += 1;
        }
    }

    let Some(best) = buckets.into_values().max_by_key(|bucket| bucket.count) else {
        return argb(255, 255, 255);
    };

    argb(
        (best.r_sum / best.count as u64) as u8,
        (best.g_sum / best.count as u64) as u8,
        (best.b_sum / best.count as u64) as u8,
    )
}

pub fn luminance(color: u32) -> f32 {
    let r = channel_r(color) as f32 / 255.0;
    let g = channel_g(color) as f32 / 255.0;
    let b = channel_b(color) as f32 / 255.0;
    0.299 * r + 0.587 * g + 0.114 * b
}

fn get_color_contrast(color: u32, bg_luminance: f32) -> f32 {
    let lum = luminance(color);
    let brighter = lum.max(bg_luminance);
    let darker = lum.min(bg_luminance);
    (brighter + 0.05) / (darker + 0.05)
}

fn get_surrounding_average_color(image: &RasterImage<'_>, text_bounds: Rect) -> u32 {
    let margin = 4;
    let sample_regions = [
        Rect {
            left: text_bounds.left.saturating_sub(margin),
            top: text_bounds.top,
            right: text_bounds.left,
            bottom: text_bounds.bottom,
        },
        Rect {
            left: text_bounds.right,
            top: text_bounds.top,
            right: (text_bounds.right + margin).min(image.width),
            bottom: text_bounds.bottom,
        },
        Rect {
            left: text_bounds.left,
            top: text_bounds.top.saturating_sub(margin),
            right: text_bounds.right,
            bottom: text_bounds.top,
        },
        Rect {
            left: text_bounds.left,
            top: text_bounds.bottom,
            right: text_bounds.right,
            bottom: (text_bounds.bottom + margin).min(image.height),
        },
    ];

    let mut total_r = 0u64;
    let mut total_g = 0u64;
    let mut total_b = 0u64;
    let mut count = 0u64;

    for region in sample_regions {
        let Some(region) = clamp_rect(region, image.width, image.height) else {
            continue;
        };
        for y in region.top..region.bottom {
            for x in region.left..region.right {
                let pixel = image.pixel_argb(x, y);
                total_r += channel_r(pixel) as u64;
                total_g += channel_g(pixel) as u64;
                total_b += channel_b(pixel) as u64;
                count += 1;
            }
        }
    }

    if count == 0 {
        argb(255, 255, 255)
    } else {
        argb(
            (total_r / count) as u8,
            (total_g / count) as u8,
            (total_b / count) as u8,
        )
    }
}

fn get_background_color_excluding_words(
    image: &RasterImage<'_>,
    text_bounds: Rect,
    word_rects: &[Rect],
) -> u32 {
    let Some(text_bounds) = clamp_rect(text_bounds, image.width, image.height) else {
        return get_surrounding_average_color(image, text_bounds);
    };
    let width = text_bounds.width();
    let height = text_bounds.height();
    if width == 0 || height == 0 {
        return get_surrounding_average_color(image, text_bounds);
    }

    let mut mask = vec![true; (width * height) as usize];
    for exclude_rect in word_rects {
        let left = exclude_rect.left.max(text_bounds.left);
        let top = exclude_rect.top.max(text_bounds.top);
        let right = exclude_rect.right.min(text_bounds.right);
        let bottom = exclude_rect.bottom.min(text_bounds.bottom);
        if left >= right || top >= bottom {
            continue;
        }
        for y in top..bottom {
            let offset_top = y - text_bounds.top;
            let row_start = (offset_top * width + (left - text_bounds.left)) as usize;
            let row_end = row_start + (right - left) as usize;
            mask[row_start..row_end].fill(false);
        }
    }

    let mut total_r = 0u64;
    let mut total_g = 0u64;
    let mut total_b = 0u64;
    let mut count = 0u64;
    for y in text_bounds.top..text_bounds.bottom {
        for x in text_bounds.left..text_bounds.right {
            let mask_index =
                ((y - text_bounds.top) * width + (x - text_bounds.left)) as usize;
            if !mask[mask_index] {
                continue;
            }
            let pixel = image.pixel_argb(x, y);
            total_r += channel_r(pixel) as u64;
            total_g += channel_g(pixel) as u64;
            total_b += channel_b(pixel) as u64;
            count += 1;
        }
    }

    if count == 0 {
        get_surrounding_average_color(image, text_bounds)
    } else {
        argb(
            (total_r / count) as u8,
            (total_g / count) as u8,
            (total_b / count) as u8,
        )
    }
}

fn get_foreground_color_by_contrast(
    image: &RasterImage<'_>,
    text_bounds: Rect,
    background_color: u32,
) -> u32 {
    let bg_luminance = luminance(background_color);
    let best_naive_color = if bg_luminance > 0.5 {
        argb(0, 0, 0)
    } else {
        argb(255, 255, 255)
    };

    let Some(bounds) = clamp_rect(text_bounds, image.width, image.height) else {
        return best_naive_color;
    };
    let width = bounds.width();
    let height = bounds.height();
    if width == 0 || height == 0 {
        return best_naive_color;
    }

    let step = (width.min(height) / 5).max(1);
    let mut color_data = std::collections::HashMap::<u32, (u32, f32, u32)>::new();

    let mut index = 0usize;
    for y in bounds.top..bounds.bottom {
        for x in bounds.left..bounds.right {
            if index % step as usize != 0 {
                index += 1;
                continue;
            }
            let pixel = image.pixel_argb(x, y);
            let contrast = get_color_contrast(pixel, bg_luminance);
            if contrast <= 1.5 {
                index += 1;
                continue;
            }

            let quantized = quantized_rgb(pixel);
            let entry = color_data.entry(quantized).or_insert((0, 0.0, pixel));
            entry.0 += 1;
            entry.1 += contrast;
            index += 1;
        }
    }

    if color_data.is_empty() {
        return best_naive_color;
    }

    let mut best_color = best_naive_color;
    let mut best_score = 0.0f32;
    for (_, (count, contrast_sum, original)) in color_data {
        if count <= 3 {
            continue;
        }
        let score = count as f32 * (contrast_sum / count as f32);
        if score > best_score {
            best_score = score;
            best_color = original;
        }
    }
    best_color
}

fn get_overlay_colors(
    image: &RasterImage<'_>,
    bounds: Rect,
    background_mode: crate::BackgroundMode,
    word_rects: Option<&[Rect]>,
) -> OverlayColors {
    match background_mode {
        crate::BackgroundMode::WhiteOnBlack => OverlayColors {
            background_argb: argb(0, 0, 0),
            foreground_argb: argb(255, 255, 255),
        },
        crate::BackgroundMode::BlackOnWhite => OverlayColors {
            background_argb: argb(255, 255, 255),
            foreground_argb: argb(0, 0, 0),
        },
        crate::BackgroundMode::AutoDetect => {
            let background_argb = match word_rects {
                Some(word_rects) if word_rects.len() > 1 => {
                    get_background_color_excluding_words(image, bounds, word_rects)
                }
                Some(_) => get_surrounding_average_color(image, bounds),
                None => sample_dominant_color(image, bounds),
            };
            let foreground_argb =
                get_foreground_color_by_contrast(image, bounds, background_argb);
            OverlayColors {
                background_argb,
                foreground_argb,
            }
        }
    }
}

pub fn sample_overlay_colors(
    rgba_bytes: &[u8],
    width: u32,
    height: u32,
    bounds: Rect,
    background_mode: crate::BackgroundMode,
    word_rects: Option<&[Rect]>,
) -> Result<OverlayColors, String> {
    let image = RasterImage::new(rgba_bytes, width, height)?;
    Ok(get_overlay_colors(&image, bounds, background_mode, word_rects))
}

fn expand_rect(rect: Rect, amount: u32) -> Rect {
    Rect {
        left: rect.left.saturating_sub(amount),
        top: rect.top.saturating_sub(amount),
        right: rect.right + amount,
        bottom: rect.bottom + amount,
    }
}

fn erase_text_region(
    image: &mut RasterImageMut,
    text_bounds: Rect,
    words: &[Rect],
    background_mode: crate::BackgroundMode,
) -> OverlayColors {
    let colors = get_overlay_colors(&image.as_image(), text_bounds, background_mode, Some(words));
    if background_mode == crate::BackgroundMode::AutoDetect {
        for word in words {
            image.fill_rect(expand_rect(*word, 2), colors.background_argb);
        }
    }
    image.fill_rect(text_bounds, colors.background_argb);
    colors
}

pub fn prepare_overlay_image(
    rgba_bytes: &[u8],
    width: u32,
    height: u32,
    blocks: &[TextBlock],
    translated_blocks: &[String],
    background_mode: crate::BackgroundMode,
    reading_order: ReadingOrder,
) -> Result<PreparedImageOverlay, String> {
    let mut image = RasterImageMut::new(rgba_bytes, width, height)?;
    let mut prepared_blocks = Vec::with_capacity(blocks.len());

    for (block, translated_text) in blocks.iter().zip(translated_blocks.iter()) {
        let block_bounds = block.bounds();
        match reading_order {
            ReadingOrder::LeftToRight => {
                let mut prepared_lines = Vec::with_capacity(block.lines.len());
                let mut block_background = argb(255, 255, 255);
                let mut block_foreground = argb(0, 0, 0);
                for (index, line) in block.lines.iter().enumerate() {
                    let colors = erase_text_region(
                        &mut image,
                        line.bounding_box,
                        &line.word_rects,
                        background_mode,
                    );
                    if index == 0 {
                        block_background = colors.background_argb;
                        block_foreground = colors.foreground_argb;
                    }
                    prepared_lines.push(PreparedTextLine {
                        text: line.text.clone(),
                        bounding_box: line.bounding_box,
                        word_rects: line.word_rects.clone(),
                        background_argb: colors.background_argb,
                        foreground_argb: colors.foreground_argb,
                    });
                }
                prepared_blocks.push(PreparedTextBlock {
                    source_text: block.source_text(),
                    translated_text: translated_text.clone(),
                    bounding_box: block_bounds,
                    lines: prepared_lines,
                    background_argb: block_background,
                    foreground_argb: block_foreground,
                });
            }
            ReadingOrder::TopToBottomLeftToRight => {
                let all_word_rects = block
                    .lines
                    .iter()
                    .flat_map(|line| line.word_rects.iter().copied())
                    .collect::<Vec<_>>();
                let colors =
                    erase_text_region(&mut image, block_bounds, &all_word_rects, background_mode);
                let prepared_lines = block
                    .lines
                    .iter()
                    .map(|line| PreparedTextLine {
                        text: line.text.clone(),
                        bounding_box: line.bounding_box,
                        word_rects: line.word_rects.clone(),
                        background_argb: colors.background_argb,
                        foreground_argb: colors.foreground_argb,
                    })
                    .collect();
                prepared_blocks.push(PreparedTextBlock {
                    source_text: block.source_text(),
                    translated_text: translated_text.clone(),
                    bounding_box: block_bounds,
                    lines: prepared_lines,
                    background_argb: colors.background_argb,
                    foreground_argb: colors.foreground_argb,
                });
            }
        }
    }

    Ok(PreparedImageOverlay {
        rgba_bytes: image.rgba,
        width,
        height,
        extracted_text: blocks
            .iter()
            .map(TextBlock::source_text)
            .collect::<Vec<_>>()
            .join("\n"),
        translated_text: translated_blocks.join("\n"),
        blocks: prepared_blocks,
    })
}

fn merge_hyphenated_words(words: Vec<WordInfo>) -> Vec<WordInfo> {
    if words.is_empty() {
        return words;
    }

    let mut result = Vec::new();
    let mut index = 0;

    while index < words.len() {
        let current_word = &words[index];
        if index == words.len() - 1 {
            result.push(current_word.clone());
            break;
        }

        if !current_word.is_last_in_line || !current_word.text.ends_with('-') {
            result.push(current_word.clone());
            index += 1;
            continue;
        }

        let next_word = &words[index + 1];
        let poor_mans_first_in_line = next_word.bounding_box.left < current_word.bounding_box.left
            && next_word.bounding_box.top > current_word.bounding_box.top;
        if !next_word.is_first_in_line && !poor_mans_first_in_line {
            result.push(current_word.clone());
            index += 1;
            continue;
        }

        let merged_text = format!(
            "{}{}",
            current_word.text.trim_end_matches('-'),
            next_word.text
        );
        let mut ghost_bbox = current_word.bounding_box;
        ghost_bbox.right += next_word.bounding_box.width();

        result.push(WordInfo {
            text: merged_text,
            confidence: current_word.confidence.min(next_word.confidence),
            bounding_box: current_word.bounding_box,
            ghost_bbox: Some(ghost_bbox),
            is_first_in_line: current_word.is_first_in_line,
            is_last_in_line: true,
            is_last_in_para: next_word.is_last_in_para,
        });

        if index + 2 >= words.len() {
            index += 2;
            continue;
        }

        let next_after_merged = &words[index + 2];
        let mut expanded_bbox = next_word.bounding_box;
        expanded_bbox.union(next_after_merged.bounding_box);
        result.push(WordInfo {
            bounding_box: expanded_bbox,
            is_first_in_line: true,
            ..next_after_merged.clone()
        });
        index += 3;
    }

    result
}

pub fn build_text_blocks(
    detected_words: &[DetectedWord],
    min_confidence: u32,
    join_without_spaces: bool,
    relax_single_char_confidence: bool,
) -> Vec<TextBlock> {
    let effective_min_confidence = if relax_single_char_confidence {
        (min_confidence.min(60)) as f32
    } else {
        min_confidence as f32
    };

    let all_words = detected_words
        .iter()
        .map(|word| WordInfo {
            text: word.text.clone(),
            confidence: word.confidence,
            bounding_box: word.bounding_box,
            ghost_bbox: None,
            is_first_in_line: word.is_at_beginning_of_para,
            is_last_in_line: word.end_line,
            is_last_in_para: word.end_para,
        })
        .collect::<Vec<_>>();

    let mut filtered_words = Vec::new();
    let mut pending_first_in_line = false;
    for (index, word) in all_words.iter().enumerate() {
        let should_include = word.confidence >= effective_min_confidence
            && (relax_single_char_confidence
                || !(word.text.chars().count() == 1
                    && word.confidence
                        < (effective_min_confidence + 5.0).min(100.0)));

        if should_include {
            filtered_words.push(WordInfo {
                is_first_in_line: word.is_first_in_line || pending_first_in_line,
                ..word.clone()
            });
            pending_first_in_line = false;
        } else {
            if word.is_first_in_line {
                pending_first_in_line = true;
            }
            if word.is_last_in_line && index > 0 {
                if let Some(previous) = filtered_words.last_mut() {
                    previous.is_last_in_line = true;
                }
            }
            if word.is_last_in_para && index > 0 {
                if let Some(previous) = filtered_words.last_mut() {
                    previous.is_last_in_para = true;
                }
            }
        }
    }

    let filtered_words = merge_hyphenated_words(filtered_words);
    let mut blocks = Vec::new();
    let mut lines = Vec::new();
    let mut current_line: Option<TextLine> = None;
    let mut last_right = 0u32;

    for word in filtered_words {
        if word.text.trim().is_empty() {
            continue;
        }

        let real_bbox = word.ghost_bbox.unwrap_or(word.bounding_box);
        let skipped_first_word = current_line
            .as_ref()
            .is_some_and(|line| word.bounding_box.right < line.bounding_box.left);
        let first_word_in_line = word.is_first_in_line || skipped_first_word;
        let last_word_in_line = word.is_last_in_line;
        let last_word_in_para = word.is_last_in_para;

        if first_word_in_line || current_line.is_none() {
            current_line = Some(TextLine {
                text: word.text.clone(),
                bounding_box: word.bounding_box,
                word_rects: vec![word.bounding_box],
            });
        } else if let Some(line) = current_line.as_mut() {
            let delta = word.bounding_box.left.saturating_sub(last_right);
            let char_width = real_bbox.width() as f32 / word.text.chars().count().max(1) as f32;
            let delta_in_chars = if char_width > 0.0 {
                delta as f32 / char_width
            } else {
                0.0
            };

            if delta_in_chars >= 3.0 {
                lines.push(line.clone());
                *line = TextLine {
                    text: word.text.clone(),
                    bounding_box: word.bounding_box,
                    word_rects: vec![word.bounding_box],
                };
                if !lines.is_empty() {
                    blocks.push(TextBlock {
                        lines: std::mem::take(&mut lines),
                    });
                }
            } else {
                if join_without_spaces || line.text.is_empty() {
                    line.text.push_str(&word.text);
                } else {
                    line.text.push(' ');
                    line.text.push_str(&word.text);
                }
                line.word_rects.push(word.bounding_box);
                line.bounding_box.union(word.bounding_box);
            }
        }

        if last_word_in_line {
            if let Some(line) = current_line.take() {
                if !line.text.trim().is_empty() {
                    lines.push(line);
                }
            }
        }

        if last_word_in_para && !lines.is_empty() {
            blocks.push(TextBlock {
                lines: std::mem::take(&mut lines),
            });
        }

        last_right = word.bounding_box.right;
    }

    if let Some(line) = current_line.take() {
        if !line.text.trim().is_empty() {
            lines.push(line);
        }
    }
    if !lines.is_empty() {
        blocks.push(TextBlock { lines });
    }

    blocks
}

#[cfg(test)]
mod tests {
    use super::{
        DetectedWord, Rect, TextBlock, TextLine, build_text_blocks, prepare_overlay_image,
    };
    use crate::{BackgroundMode, ReadingOrder};

    fn word(
        text: &str,
        left: u32,
        top: u32,
        right: u32,
        bottom: u32,
        is_first_in_line: bool,
        is_last_in_line: bool,
        is_last_in_para: bool,
    ) -> DetectedWord {
        DetectedWord {
            text: text.to_string(),
            confidence: 95.0,
            bounding_box: Rect {
                left,
                top,
                right,
                bottom,
            },
            is_at_beginning_of_para: is_first_in_line,
            end_line: is_last_in_line,
            end_para: is_last_in_para,
        }
    }

    #[test]
    fn translation_text_flattens_wrapped_lines_into_one_paragraph() {
        let detected_words = vec![
            word("relax", 52, 129, 103, 145, true, false, false),
            word("slightly", 115, 129, 192, 150, false, false, false),
            word("as", 202, 134, 224, 144, false, false, false),
            word("the", 235, 129, 267, 145, false, false, false),
            word("reprieve", 279, 133, 365, 150, false, false, false),
            word("of", 376, 128, 395, 144, false, false, false),
            word("warmth", 404, 127, 486, 144, false, false, false),
            word("began", 498, 127, 560, 148, false, false, false),
            word("to", 571, 129, 590, 143, false, false, false),
            word("press", 601, 131, 657, 148, false, false, false),
            word("against", 668, 128, 744, 147, false, true, false),
            word("his", 51, 158, 80, 174, true, false, false),
            word("frozen", 90, 159, 155, 174, false, false, false),
            word("cheeks.", 164, 158, 243, 174, false, true, true),
        ];

        let blocks = build_text_blocks(&detected_words, 75, false, false);
        assert_eq!(blocks.len(), 1);
        assert_eq!(
            blocks[0].source_text(),
            "relax slightly as the reprieve of warmth began to press against\nhis frozen cheeks."
        );
        assert_eq!(
            blocks[0].translation_text(),
            "relax slightly as the reprieve of warmth began to press against his frozen cheeks."
        );
    }

    #[test]
    fn prepare_overlay_image_erases_left_to_right_lines_without_touching_gap() {
        let width = 8;
        let height = 8;
        let gap_color = 0xFF12_34_56u32;
        let mut rgba = Vec::with_capacity((width * height * 4) as usize);
        for y in 0..height {
            let color = if y == 3 { gap_color } else { 0xFF00_0000 };
            for _ in 0..width {
                rgba.extend_from_slice(&color.to_ne_bytes());
            }
        }

        let blocks = vec![TextBlock {
            lines: vec![
                TextLine {
                    text: "top".to_string(),
                    bounding_box: Rect {
                        left: 1,
                        top: 1,
                        right: 7,
                        bottom: 3,
                    },
                    word_rects: vec![Rect {
                        left: 1,
                        top: 1,
                        right: 7,
                        bottom: 3,
                    }],
                },
                TextLine {
                    text: "bottom".to_string(),
                    bounding_box: Rect {
                        left: 1,
                        top: 4,
                        right: 7,
                        bottom: 6,
                    },
                    word_rects: vec![Rect {
                        left: 1,
                        top: 4,
                        right: 7,
                        bottom: 6,
                    }],
                },
            ],
        }];
        let translated = vec!["translated text".to_string()];

        let prepared = prepare_overlay_image(
            &rgba,
            width,
            height,
            &blocks,
            &translated,
            BackgroundMode::BlackOnWhite,
            ReadingOrder::LeftToRight,
        )
        .expect("overlay should prepare");

        let gap_index = ((3 * width + 2) * 4) as usize;
        let gap_pixel = u32::from_ne_bytes(
            prepared.rgba_bytes[gap_index..gap_index + 4]
                .try_into()
                .expect("gap pixel"),
        );
        assert_eq!(gap_pixel, gap_color);

        let erased_index = ((1 * width + 2) * 4) as usize;
        let erased_pixel = u32::from_ne_bytes(
            prepared.rgba_bytes[erased_index..erased_index + 4]
                .try_into()
                .expect("erased pixel"),
        );
        assert_eq!(erased_pixel, 0xFFFF_FFFF);
        assert_eq!(prepared.blocks[0].lines.len(), 2);
        assert_eq!(prepared.blocks[0].lines[0].foreground_argb, 0xFF00_0000);
    }
}
