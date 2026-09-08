/**
 * Cross-cutting machinery: things every feature needs and none of them owns.
 *
 * <ul>
 *   <li>{@code error} — the RFC 9457 problem documents this API returns instead of stack traces
 *       or whitelabel pages</li>
 *   <li>{@code hash} — SHA-256, HMAC, and the canonical JSON rendering that makes a hash of the
 *       same data come out the same everywhere</li>
 *   <li>{@code idempotency} — the ledger that stops a retried request becoming a second
 *       application</li>
 *   <li>{@link com.zenalyst.housing.platform.ClockConfiguration} — time is injected, never read
 *       from a static, so that deadline behaviour can be tested at a chosen instant</li>
 * </ul>
 */
package com.zenalyst.housing.platform;
