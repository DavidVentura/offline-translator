use std::sync::Arc;

use adblock::Engine as InnerEngine;
use adblock::cosmetic_filter_cache::ProceduralOrActionFilter;
use adblock::lists::ParseOptions;
use adblock::request::Request;

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum AdblockException {
    #[error("deserialization failed: {reason}")]
    Deserialize { reason: String },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum AdblockRequestType {
    Document,
    Subdocument,
    Stylesheet,
    Script,
    Image,
    Font,
    Media,
    Object,
    Xhr,
    Fetch,
    WebSocket,
    Ping,
    Other,
}

impl AdblockRequestType {
    fn as_str(&self) -> &'static str {
        match self {
            AdblockRequestType::Document => "document",
            AdblockRequestType::Subdocument => "subdocument",
            AdblockRequestType::Stylesheet => "stylesheet",
            AdblockRequestType::Script => "script",
            AdblockRequestType::Image => "image",
            AdblockRequestType::Font => "font",
            AdblockRequestType::Media => "media",
            AdblockRequestType::Object => "object",
            AdblockRequestType::Xhr => "xmlhttprequest",
            AdblockRequestType::Fetch => "xmlhttprequest",
            AdblockRequestType::WebSocket => "websocket",
            AdblockRequestType::Ping => "ping",
            AdblockRequestType::Other => "other",
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AdblockVerdict {
    pub matched: bool,
    pub exception: bool,
    pub important: bool,
    pub redirect: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct AdblockCosmetic {
    pub hide_selectors: Vec<String>,
    pub style_rules: Vec<String>,
    pub procedural_filters: Vec<String>,
    pub exceptions: Vec<String>,
    pub injected_script: String,
    pub generichide: bool,
}

impl AdblockVerdict {
    fn allow() -> Self {
        Self {
            matched: false,
            exception: false,
            important: false,
            redirect: None,
        }
    }
}

#[derive(uniffi::Object)]
pub struct AdblockEngine {
    inner: InnerEngine,
}

#[uniffi::export]
impl AdblockEngine {
    #[uniffi::constructor]
    pub fn from_rules(rules_text: String) -> Arc<Self> {
        let inner = InnerEngine::from_rules(rules_text.lines(), ParseOptions::default());
        Arc::new(Self { inner })
    }

    #[uniffi::constructor]
    pub fn deserialize(bytes: Vec<u8>) -> Result<Arc<Self>, AdblockException> {
        let mut inner = InnerEngine::default();
        inner
            .deserialize(&bytes)
            .map_err(|e| AdblockException::Deserialize {
                reason: format!("{e:?}"),
            })?;
        Ok(Arc::new(Self { inner }))
    }

    pub fn serialize(&self) -> Vec<u8> {
        self.inner.serialize()
    }

    pub fn check_request(
        &self,
        url: String,
        source_url: String,
        request_type: AdblockRequestType,
    ) -> AdblockVerdict {
        let request = match Request::new(&url, &source_url, request_type.as_str()) {
            Ok(r) => r,
            Err(_) => return AdblockVerdict::allow(),
        };
        let result = self.inner.check_network_request(&request);
        AdblockVerdict {
            matched: result.matched,
            exception: result.exception.is_some(),
            important: result.important,
            redirect: result.redirect,
        }
    }

    pub fn hidden_class_id_selectors(
        &self,
        classes: Vec<String>,
        ids: Vec<String>,
        exceptions: Vec<String>,
    ) -> Vec<String> {
        let exception_set: std::collections::HashSet<String> = exceptions.into_iter().collect();
        self.inner
            .hidden_class_id_selectors(classes.iter(), ids.iter(), &exception_set)
    }

    pub fn cosmetic_resources(&self, url: String) -> AdblockCosmetic {
        let r = self.inner.url_cosmetic_resources(&url);
        let mut hide_selectors: Vec<String> = r.hide_selectors.into_iter().collect();
        let mut style_rules: Vec<String> = Vec::new();
        let mut procedural_filters: Vec<String> = Vec::new();

        for proc_json in &r.procedural_actions {
            let Ok(filter) = serde_json::from_str::<ProceduralOrActionFilter>(proc_json) else {
                continue;
            };
            if let Some((selector, style)) = filter.as_css() {
                if style == "display: none !important" {
                    hide_selectors.push(selector);
                } else {
                    style_rules.push(format!("{selector} {{ {style} }}"));
                }
            } else {
                procedural_filters.push(proc_json.clone());
            }
        }

        AdblockCosmetic {
            hide_selectors,
            style_rules,
            procedural_filters,
            exceptions: r.exceptions.into_iter().collect(),
            injected_script: r.injected_script,
            generichide: r.generichide,
        }
    }
}
