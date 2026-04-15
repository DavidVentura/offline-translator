use crate::catalog::CatalogSnapshot;
use crate::ocr::{OverlayColors, Rect, sample_overlay_colors};
use crate::routing::{NothingReason, detect_language_robust_code};
use crate::translate::{TokenAlignment, translate_texts_with_alignment_in_snapshot};
use crate::{BackgroundMode, BergamotEngine};

#[derive(Debug, Clone, PartialEq)]
pub struct TextStyle {
    pub text_color: Option<i32>,
    pub bg_color: Option<i32>,
    pub text_size: Option<f32>,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
}

impl TextStyle {
    fn has_real_background(&self) -> bool {
        let Some(color) = self.bg_color else {
            return false;
        };
        if color == 0 || color == 1 || color == -1 {
            return false;
        }
        ((color as u32) >> 24) != 0
    }

    fn normalized_text_color(&self) -> Option<u32> {
        let color = self.text_color?;
        if ((color as u32) >> 24) == 0 {
            None
        } else {
            Some(color as u32)
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct StyledFragment {
    pub text: String,
    pub bounds: Rect,
    pub style: Option<TextStyle>,
    pub layout_group: i32,
    pub translation_group: i32,
    pub cluster_group: i32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct StyleSpan {
    pub start: i32,
    pub end: i32,
    pub style: Option<TextStyle>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TranslationSegment {
    pub start: i32,
    pub end: i32,
    pub translation_group: i32,
}

#[derive(Debug, Clone, PartialEq)]
struct TranslatableBlock {
    text: String,
    bounds: Rect,
    style_spans: Vec<StyleSpan>,
    segments: Vec<TranslationSegment>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct TranslatedStyledBlock {
    pub text: String,
    pub bounds: Rect,
    pub style_spans: Vec<StyleSpan>,
    pub background_argb: u32,
    pub foreground_argb: u32,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct StructuredTranslationResult {
    pub blocks: Vec<TranslatedStyledBlock>,
    pub nothing_reason: Option<NothingReason>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OverlayScreenshot {
    pub rgba_bytes: Vec<u8>,
    pub width: i32,
    pub height: i32,
}

pub fn translate_structured_fragments_in_snapshot(
    engine: &mut BergamotEngine,
    snapshot: &CatalogSnapshot,
    fragments: &[StyledFragment],
    forced_source_code: Option<&str>,
    target_code: &str,
    available_language_codes: &[String],
    screenshot: Option<&OverlayScreenshot>,
    background_mode: BackgroundMode,
) -> Result<StructuredTranslationResult, String> {
    let blocks = cluster_fragments_into_blocks(fragments);
    if blocks.is_empty() {
        return Ok(StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: Some(NothingReason::NoTranslatableText),
            error_message: None,
        });
    }

    let combined_text = blocks
        .iter()
        .map(|block| block.text.as_str())
        .collect::<Vec<_>>()
        .join(" ");
    let Some(source_code) = forced_source_code
        .map(ToOwned::to_owned)
        .or_else(|| detect_language_robust_code(&combined_text, None, available_language_codes))
    else {
        return Ok(StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: Some(NothingReason::CouldNotDetect),
            error_message: None,
        });
    };

    if source_code == target_code {
        return Ok(StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: Some(NothingReason::AlreadyTargetLanguage),
            error_message: None,
        });
    }

    #[derive(Clone)]
    struct SegmentRef {
        block_index: usize,
        segment: TranslationSegment,
    }

    let mut all_segment_texts = Vec::new();
    let mut segment_refs = Vec::new();
    for (block_index, block) in blocks.iter().enumerate() {
        for segment in &block.segments {
            let start = segment.start as usize;
            let end = segment.end as usize;
            all_segment_texts.push(block.text[start..end].to_string());
            segment_refs.push(SegmentRef {
                block_index,
                segment: segment.clone(),
            });
        }
    }

    let Some(translations) = translate_texts_with_alignment_in_snapshot(
        engine,
        snapshot,
        &source_code,
        target_code,
        &all_segment_texts,
    )
    else {
        return Ok(StructuredTranslationResult {
            blocks: Vec::new(),
            nothing_reason: None,
            error_message: Some(format!(
                "Language pair {} -> {} not installed",
                source_code, target_code
            )),
        });
    };
    let translations = translations?;

    let translated_blocks = blocks
        .iter()
        .enumerate()
        .map(|(block_index, source_block)| {
            let block_segment_results = translations
                .iter()
                .zip(segment_refs.iter())
                .filter(|(_, segment_ref)| segment_ref.block_index == block_index)
                .collect::<Vec<_>>();

            let mut translated_text = String::new();
            let mut segment_alignments = Vec::new();
            let mut translated_segments = Vec::new();

            for (translation, segment_ref) in block_segment_results {
                translated_segments
                    .push((segment_ref.segment.clone(), translation.translated_text.clone()));
                segment_alignments
                    .push((segment_ref.segment.clone(), translation.alignments.clone()));
                translated_text.push_str(&translation.translated_text);
            }

            let style_spans = map_styles_to_segmented_translation(
                source_block,
                &segment_alignments,
                &translated_segments,
            );
            let colors = resolve_block_colors(
                screenshot,
                source_block.bounds,
                source_block
                    .style_spans
                    .first()
                    .and_then(|span| span.style.as_ref()),
                background_mode,
            )?;

            Ok(TranslatedStyledBlock {
                text: translated_text,
                bounds: source_block.bounds,
                style_spans,
                background_argb: colors.background_argb,
                foreground_argb: colors.foreground_argb,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;

    Ok(StructuredTranslationResult {
        blocks: translated_blocks,
        nothing_reason: None,
        error_message: None,
    })
}

fn cluster_fragments_into_blocks(fragments: &[StyledFragment]) -> Vec<TranslatableBlock> {
    if fragments.is_empty() {
        return Vec::new();
    }

    let line_height = lower_quartile_height(fragments);
    let block_gap_threshold = ((line_height as f32) * 0.5) as i32;

    let mut block_groups: Vec<Vec<StyledFragment>> = Vec::new();
    let mut block_bounds: Vec<Rect> = Vec::new();
    let mut block_layout_group_ids = Vec::new();
    let mut block_cluster_group_ids = Vec::new();

    for fragment in fragments {
        let mut merged = false;
        for i in 0..block_groups.len() {
            if block_layout_group_ids[i] != fragment.layout_group {
                continue;
            }
            if block_cluster_group_ids[i] != fragment.cluster_group {
                continue;
            }
            let bb: Rect = block_bounds[i];
            let vertical_overlap =
                bb.bottom.min(fragment.bounds.bottom) - bb.top.max(fragment.bounds.top);
            let vertical_gap = fragment.bounds.top - bb.bottom;
            let horizontal_overlap =
                bb.right.min(fragment.bounds.right) - bb.left.max(fragment.bounds.left);
            let horizontal_gap =
                bb.left.max(fragment.bounds.left) - bb.right.min(fragment.bounds.right);
            let horizontal_nearby = horizontal_gap <= line_height;

            if (vertical_overlap > 0 && horizontal_nearby)
                || ((0..=block_gap_threshold).contains(&vertical_gap)
                    && horizontal_overlap > 0)
            {
                block_groups[i].push(fragment.clone());
                block_bounds[i].union(fragment.bounds);
                merged = true;
                break;
            }
        }
        if !merged {
            block_groups.push(vec![fragment.clone()]);
            block_bounds.push(fragment.bounds);
            block_layout_group_ids.push(fragment.layout_group);
            block_cluster_group_ids.push(fragment.cluster_group);
        }
    }

    block_groups
        .into_iter()
        .zip(block_bounds)
        .map(|(group, bounds)| build_block(&group, bounds))
        .collect()
}

fn build_block(fragments: &[StyledFragment], bounds: Rect) -> TranslatableBlock {
    let lines = cluster_into_lines(fragments);
    let mut text = String::new();
    let mut spans = Vec::new();
    let mut segments = Vec::new();
    let mut current_trans_group = fragments
        .first()
        .map(|fragment| fragment.translation_group)
        .unwrap_or_default();
    let mut segment_start = 0i32;

    for (line_index, line) in lines.iter().enumerate() {
        if line_index > 0 {
            text.push('\n');
        }
        for (fragment_index, fragment) in line.iter().enumerate() {
            if fragment.translation_group != current_trans_group {
                if (text.len() as i32) > segment_start {
                    segments.push(TranslationSegment {
                        start: segment_start,
                        end: text.len() as i32,
                        translation_group: current_trans_group,
                    });
                }
                current_trans_group = fragment.translation_group;
                segment_start = text.len() as i32;
            }
            if fragment_index > 0
                && !text.is_empty()
                && !text.chars().last().is_some_and(char::is_whitespace)
            {
                text.push(' ');
            }
            let start = text.len() as i32;
            text.push_str(&fragment.text);
            if fragment.style.is_some() {
                spans.push(StyleSpan {
                    start,
                    end: text.len() as i32,
                    style: fragment.style.clone(),
                });
            }
        }
    }

    if (text.len() as i32) > segment_start {
        segments.push(TranslationSegment {
            start: segment_start,
            end: text.len() as i32,
            translation_group: current_trans_group,
        });
    }

    TranslatableBlock {
        text,
        bounds,
        style_spans: spans,
        segments,
    }
}

fn cluster_into_lines(fragments: &[StyledFragment]) -> Vec<Vec<StyledFragment>> {
    if fragments.is_empty() {
        return Vec::new();
    }

    let median_height = median_fragment_height(fragments);
    let line_threshold = ((median_height as f32) * 0.35) as i32;
    let line_threshold = line_threshold.max(1);

    let mut lines: Vec<Vec<StyledFragment>> = Vec::new();
    let mut line_tops: Vec<i32> = Vec::new();
    let mut line_bottoms: Vec<i32> = Vec::new();

    for fragment in fragments {
        let mut best_line = None;
        for i in 0..lines.len() {
            let center_delta = (fragment.bounds.center_y()
                - ((line_tops[i] + line_bottoms[i]) / 2))
                .abs();
            let vertical_overlap =
                line_bottoms[i].min(fragment.bounds.bottom) - line_tops[i].max(fragment.bounds.top);
            if vertical_overlap > 0 || center_delta <= line_threshold {
                best_line = Some(i);
                break;
            }
        }

        if let Some(i) = best_line {
            lines[i].push(fragment.clone());
            line_tops[i] = line_tops[i].min(fragment.bounds.top);
            line_bottoms[i] = line_bottoms[i].max(fragment.bounds.bottom);
        } else {
            lines.push(vec![fragment.clone()]);
            line_tops.push(fragment.bounds.top);
            line_bottoms.push(fragment.bounds.bottom);
        }
    }

    let mut line_indices = (0..lines.len()).collect::<Vec<_>>();
    line_indices.sort_by_key(|index| line_tops[*index]);
    line_indices
        .into_iter()
        .map(|index| lines[index].clone())
        .collect()
}

fn map_styles_to_segmented_translation(
    source_block: &TranslatableBlock,
    segment_alignments: &[(TranslationSegment, Vec<TokenAlignment>)],
    translated_segments: &[(TranslationSegment, String)],
) -> Vec<StyleSpan> {
    let mut result = Vec::new();
    let mut target_offset = 0i32;

    for (segment, translated) in translated_segments {
        let alignments = segment_alignments
            .iter()
            .find(|(aligned_segment, _)| aligned_segment == segment)
            .map(|(_, alignments)| alignments.as_slice())
            .unwrap_or(&[]);

        for alignment in alignments {
            let src_mid = segment.start + ((alignment.src_begin + alignment.src_end) / 2) as i32;
            let Some(matching_span) = source_block
                .style_spans
                .iter()
                .find(|span| src_mid >= span.start && src_mid < span.end)
            else {
                continue;
            };
            result.push(StyleSpan {
                start: target_offset + alignment.tgt_begin as i32,
                end: target_offset + alignment.tgt_end as i32,
                style: matching_span.style.clone(),
            });
        }

        target_offset += translated.len() as i32;
    }

    merge_style_spans(result)
}

fn merge_style_spans(mut spans: Vec<StyleSpan>) -> Vec<StyleSpan> {
    if spans.is_empty() {
        return Vec::new();
    }
    spans.sort_by_key(|span| span.start);
    let mut merged = vec![spans[0].clone()];
    for span in spans.into_iter().skip(1) {
        let last = merged.last_mut().expect("merged has at least one span");
        if span.style == last.style && span.start <= last.end {
            last.end = last.end.max(span.end);
        } else {
            merged.push(span);
        }
    }
    merged
}

fn resolve_block_colors(
    screenshot: Option<&OverlayScreenshot>,
    bounds: Rect,
    first_style: Option<&TextStyle>,
    background_mode: BackgroundMode,
) -> Result<OverlayColors, String> {
    let sampled_colors = match screenshot {
        Some(screenshot) => Some(sample_overlay_colors(
            &screenshot.rgba_bytes,
            screenshot.width,
            screenshot.height,
            bounds,
            background_mode,
            None,
        )?),
        None => None,
    };

    let style_fg = first_style.and_then(TextStyle::normalized_text_color);
    let style_bg = first_style
        .filter(|style| style.has_real_background())
        .and_then(|style| style.bg_color)
        .map(|color| color as u32);

    if let Some(background_argb) = style_bg {
        return Ok(OverlayColors {
            background_argb,
            foreground_argb: style_fg
                .or_else(|| sampled_colors.map(|colors| colors.foreground_argb))
                .unwrap_or(0xFF00_0000),
        });
    }

    if let Some(sampled_colors) = sampled_colors {
        return Ok(OverlayColors {
            background_argb: sampled_colors.background_argb,
            foreground_argb: style_fg.unwrap_or(sampled_colors.foreground_argb),
        });
    }

    if let Some(foreground_argb) = style_fg {
        let luminance = super::ocr::luminance(foreground_argb);
        let background_argb = if luminance > 0.5 {
            0xFF00_0000
        } else {
            0xFFFF_FFFF
        };
        return Ok(OverlayColors {
            background_argb,
            foreground_argb,
        });
    }

    Ok(OverlayColors {
        background_argb: 0xFFFF_FFFF,
        foreground_argb: 0xFF00_0000,
    })
}

fn median_fragment_height(fragments: &[StyledFragment]) -> i32 {
    let mut heights = fragments
        .iter()
        .map(|fragment| fragment.bounds.height())
        .collect::<Vec<_>>();
    heights.sort_unstable();
    heights[heights.len() / 2].max(1)
}

fn lower_quartile_height(fragments: &[StyledFragment]) -> i32 {
    let mut heights = fragments
        .iter()
        .map(|fragment| fragment.bounds.height())
        .collect::<Vec<_>>();
    heights.sort_unstable();
    heights[heights.len() / 4].max(1)
}

#[cfg(test)]
mod tests {
    use super::{Rect, StyledFragment, TextStyle, cluster_fragments_into_blocks};

    #[test]
    fn clusters_fragments_into_two_lines_one_block() {
        let fragments = vec![
            StyledFragment {
                text: "Hello".into(),
                bounds: Rect {
                    left: 0,
                    top: 0,
                    right: 40,
                    bottom: 20,
                },
                style: Some(TextStyle {
                    text_color: None,
                    bg_color: None,
                    text_size: None,
                    bold: false,
                    italic: false,
                    underline: false,
                    strikethrough: false,
                }),
                layout_group: 0,
                translation_group: 0,
                cluster_group: 0,
            },
            StyledFragment {
                text: "world".into(),
                bounds: Rect {
                    left: 48,
                    top: 0,
                    right: 92,
                    bottom: 20,
                },
                style: None,
                layout_group: 0,
                translation_group: 0,
                cluster_group: 0,
            },
            StyledFragment {
                text: "again".into(),
                bounds: Rect {
                    left: 0,
                    top: 28,
                    right: 48,
                    bottom: 48,
                },
                style: None,
                layout_group: 0,
                translation_group: 0,
                cluster_group: 0,
            },
        ];

        let blocks = cluster_fragments_into_blocks(&fragments);
        assert_eq!(blocks.len(), 1);
        assert_eq!(blocks[0].text, "Hello world\nagain");
    }
}
