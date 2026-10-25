# Source fixtures

Each child directory is a separate project root. These are small native-v1 authoring fixtures. They are not a host-parser or Android runtime implementation, and successful GLSL compilation alone is not a rendering conformance result.

| Directory | Exercises | Expected result |
| --- | --- | --- |
| `minimal` | No manifest or host metadata | Animated color gradient using existing uniforms |
| `texture` | Adjacent image input and sampler override | Repeated small checker texture; local image is included |
| `feedback` | Shader input, include, implicit backbuffer | Touch deposits a decaying field rendered through a palette |
| `previous` | Two inputs referencing current/previous of the same pass | Current field, scaled difference, and previous field in RGB |
| `explicit-entry` | Optional manifest and non-default entry | Same minimal gradient from a nested entry |

All GLSL examples use GLSL ES 1.00 syntax with explicit declarations and precision. `se input` lines are host metadata consumed before driver compilation. Paths must be validated/resolved by the future host implementation. Do not send unexpanded `#include` directives directly to the GLES driver.

The 2×2 PNG fixture is generated for this package and needs no external resource library. The temporal examples illustrate behavior; acceptance R05/R06 additionally require deterministic numeric fixtures with independently expected pixel values.
