/**
 * Public data contract of the broker: the types an application touches when implementing the
 * {@link eu.derfniw.oauthbroker.runtime.spi.UpstreamUserResolver} SPI or reading the decoded
 * identity. Kept separate from the internal envelope payloads ({@code …runtime.envelope}), wire
 * DTOs ({@code …runtime.dto}), and exception hierarchy ({@code …runtime.error}).
 */
@NullMarked
package eu.derfniw.oauthbroker.runtime.api;

import org.jspecify.annotations.NullMarked;
