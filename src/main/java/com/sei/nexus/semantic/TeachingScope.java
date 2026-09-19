package com.sei.nexus.semantic;

/**
 * /TeachZevra's scope selector — reused nowhere else in the codebase, since no existing scope
 * enum fit (investigation found no pre-existing tenant-vs-connection scope representation to
 * reuse). Deliberately just these two values — no free-form scope, no inference.
 */
public enum TeachingScope {
    TENANT,
    CONNECTION
}
