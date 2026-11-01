/**
 * Shared infrastructure: error handling (RFC 9457), request IDs, logging, clock.
 *
 * <p>Module public API: only the public types in this root package may be used by other modules.
 * Subpackages (for example {@code internal}, {@code web}, {@code persistence}) are private to the module.
 * Allowed module dependencies: none.
 */
package com.aiexpense.common;
