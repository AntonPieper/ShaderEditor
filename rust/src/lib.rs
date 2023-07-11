use std::ffi::c_void;
use std::fmt::Debug;
use std::ops::Range;
use std::time::Instant;

use jni::{JavaVM, JNIEnv, NativeMethod};
use jni::objects::{
    GlobalRef, JClass, JIntArray, JMethodID, JObject, JObjectArray, JString, JValue,
};
use jni::signature::{Primitive, ReturnType};
use jni::strings::JNIString;
use jni::sys::{jint, jlong, JNI_VERSION_1_2, jvalue};
use tree_sitter_highlight::{Highlight, HighlightConfiguration, Highlighter, HighlightEvent};
use unicode_segmentation::UnicodeSegmentation;

const GLSL_HIGHLIGHTS_QUERY: &str = include_str!("../tree-sitter-glsl/queries/highlights.scm");
const GLSL_LOCALS_QUERY: &str = include_str!("../tree-sitter-glsl/queries/locals.scm");
const GLSL_INJECTIONS_QUERY: &str = include_str!("../tree-sitter-glsl/queries/injections.scm");
// const COMMENT_HIGHLIGHTS_QUERY: &str = include_str!("../tree-sitter-comment/queries/highlights.scm");

#[derive(Debug)]
struct CurrentHighlight {
    highlight: i32,
    range: Range<i32>,
}

struct GlslHighlighter {
    highlighter: Highlighter,
    glsl_config: HighlightConfiguration,
    set_span_id: JMethodID,
    colors: Vec<i32>,
    span_class: GlobalRef,
    span_init: JMethodID,
}

const INVALID_JNI_VERSION: jint = 0;

fn log<U: AsRef<str>>(env: &mut JNIEnv<'_>, tag: &str, msg: U) {
    env.call_static_method(
        "android/util/Log",
        "d",
        "(Ljava/lang/String;Ljava/lang/String;)I",
        &[
            JValue::Object(&env.new_string(tag).unwrap()),
            JValue::Object(&env.new_string(msg).unwrap()),
        ],
    ).unwrap();
}

fn create_native_method(name: &str, sig: &str, fn_ptr: *mut c_void) -> NativeMethod {
    NativeMethod {
        name: JNIString::from(name),
        sig: JNIString::from(sig),
        fn_ptr,
    }
}

#[allow(non_snake_case)]
#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: JavaVM, _: *mut c_void) -> jint {
    let mut env = vm.get_env().expect("Cannot get reference to the JNIEnv");
    let Ok(class) = env.find_class("de/markusfisch/android/shadereditor/highlighter/Highlighter$Native") else { return INVALID_JNI_VERSION; };
    match env.register_native_methods(
        class,
        &[
            create_native_method(
                "createHighlighter",
                "([Ljava/lang/String;[I)J",
                create_highlighter as *mut c_void,
            ),
            create_native_method(
                "deleteHighlighter",
                "(J)V",
                delete_highlighter as *mut c_void,
            ),
            create_native_method(
                "highlight",
                "(JLjava/lang/String;Landroid/text/Spannable;)V",
                highlight as *mut c_void,
            ),
        ],
    ) {
        Ok(_) => JNI_VERSION_1_2,
        Err(_) => INVALID_JNI_VERSION,
    }
}

/// Create a highlighter.

fn create_highlighter<'a>(
    mut env: JNIEnv<'a>,
    _: JClass<'a>,
    names: JObjectArray<'a>,
    colors: JIntArray<'a>,
) -> jlong {
    let Ok(length) = env.get_array_length(&names) else {
        return 0 as jlong;
    };
    let Ok(names): Result<Vec<_>, _> = (0..length).map(|i|
        env.get_object_array_element(&names, i).and_then(|e|
            env.get_string(&JString::from(e)).map(String::from))).collect() else {
        return 0 as jlong;
    };
    let highlighter = Highlighter::new();
    let glsl_language = tree_sitter_glsl::language();
    let mut glsl_config = HighlightConfiguration::new(
        glsl_language,
        GLSL_HIGHLIGHTS_QUERY,
        GLSL_INJECTIONS_QUERY,
        GLSL_LOCALS_QUERY,
    ).unwrap();
    glsl_config.configure(&names);
    let spannable_class = env.find_class("android/text/Spannable").unwrap();
    let span_class = env.find_class("android/text/style/ForegroundColorSpan").and_then(|class| env.new_global_ref(class)).unwrap();
    let span_init = env.get_method_id(&span_class, "<init>", "(I)V").unwrap();
    let set_span_id = env.get_method_id(spannable_class, "setSpan", "(Ljava/lang/Object;III)V").unwrap();
    Box::into_raw(Box::new(GlslHighlighter {
        highlighter,
        glsl_config,
        set_span_id,
        colors: {
            let mut tmp = vec![0; names.len()];
            env.get_int_array_region(colors, 0, &mut tmp).unwrap();
            tmp
        },
        span_class,
        span_init,
    })) as jlong
}

/// Deletes a Highlighter.
///
/// # Safety
///
/// Undefined behavior if the pointer does not point to a `GlslHighlighter`
/// (constructed by `createHighlighter`), a null pointer is also undefined behavior.

unsafe fn delete_highlighter<'a>(_: JNIEnv<'a>, _: JClass<'a>, pointer: jlong) {
    let _ = Box::from_raw(pointer as *mut GlslHighlighter);
}

fn build_offset_index_lookup(s: &str) -> Vec<usize> {
    let mut offset_index_lookup = Vec::new();
    for (index, char) in s.graphemes(true).enumerate() {
        for _ in 0..char.len() {
            offset_index_lookup.push(index);
        }
    }
    offset_index_lookup
}

// const TEST: &str = include_str!("../test.glsl");

/// Highlight code using the highlighter pointed to by `pointer`.
/// Returns data in following format: [index into highlight names, starting byte, ending byte, ...]
///
/// # Safety
///
/// Undefined behavior if the pointer does not point to a `GlslHighlighter`
/// (constructed by `createHighlighter`), a null pointer is also undefined behavior.

fn highlight<'a>(
    mut env: JNIEnv<'a>,
    _: JClass<'a>,
    pointer: jlong,
    source: JString<'a>,
    spannable: JObject<'a>,
) {
    let start = Instant::now();
    let Ok(source) = env.get_string(&source) else { return; };
    let Ok(source) = source.to_str() else { return; };
    log(&mut env,"JNI", format!("string conversion took {:?}", start.elapsed()));
    if source.is_empty() { return; }
    let code_points = build_offset_index_lookup(source);
    let highlighter = unsafe { &mut *(pointer as *mut GlslHighlighter) };
    let highlights = highlighter.highlighter.highlight(&highlighter.glsl_config, source.as_bytes(), None, |_| None).unwrap();
    let mut current_highlight = CurrentHighlight {
        highlight: -1,
        range: 0..0,
    };
    let start = Instant::now();
    for event in highlights {
        match event.unwrap() {
            HighlightEvent::HighlightStart(Highlight(highlight)) => {
                current_highlight.highlight = highlight as i32;
            }
            HighlightEvent::Source { start, end } => {
                if current_highlight.highlight != -1 {
                    current_highlight.range.start = code_points[start] as i32;
                    current_highlight.range.end = (code_points[end - 1] + 1) as i32;
                }
            }
            HighlightEvent::HighlightEnd => {
                unsafe {
                    let highlight = env.new_object_unchecked(
                        &highlighter.span_class,
                        highlighter.span_init,
                        &[jvalue {
                            i: highlighter.colors[current_highlight.highlight as usize],
                        }],
                    ).unwrap();
                    env.call_method_unchecked(
                        &spannable,
                        highlighter.set_span_id,
                        ReturnType::Primitive(Primitive::Void),
                        &[
                            jvalue {
                                l: highlight.as_raw(),
                            },
                            jvalue {
                                i: current_highlight.range.start,
                            },
                            jvalue {
                                i: current_highlight.range.end,
                            },
                            jvalue { i: 33 },
                        ],
                    ).unwrap();
                }
                current_highlight.highlight = -1;
            }
        }
    }
    log(&mut env,"JNI", format!("highlights took {:?}", start.elapsed()));

}
