package com.github.dimitryivaniuta.common.security;

/** Well-known claim keys. */
public final class Claims {
    private Claims() {}
    public static final String SCOPE  = "scope";  // space-delimited string
    public static final String SCP    = "scp";    // array or space-delimited (fallback)
    public static final String PERM   = "perm";   // optional list of permissions
    public static final String TENANT = "mt";     // your tenant claim
}