/**
 * JWT resource-server security config for the identity context's inbound adapter (AD-1). The
 * sole seam that turns a validated token into the caller's identity — nothing downstream reads
 * identity from anywhere else (AR10).
 */
package de.sgart.identity.adapter.in.security;
