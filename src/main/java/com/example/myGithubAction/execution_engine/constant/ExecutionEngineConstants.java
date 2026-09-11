package com.example.myGithubAction.execution_engine.constant;

import java.util.*;
import java.util.regex.Pattern;

public final class ExecutionEngineConstants {
        /**
     * Commands that are absolutely forbidden
     */
    public static final Set<String> FORBIDDEN_COMMANDS = new HashSet<>(Arrays.asList(
            "rm", "rmdir", "shred", "dd",           // Destructive commands
            "mkfs", "mkfs.ext", "mkfs.ntfs",       // Format commands
            "fdisk", "parted", "cfdisk",           // Partition commands
            "reboot", "shutdown", "halt",          // System control
            "fork", ":(){ :|:& };:",               // Fork bomb
            "sudo", "su",                          // Privilege escalation
            "chmod", "chown", "chgrp",            // Permission modification (with 777/000)
            "passwd", "usermod",                   // User management
            "iptables", "firewall-cmd",           // Firewall/network isolation
            "systemctl", "service",               // System services (with dangerous args)
            "kill", "killall",                    // Process termination (system critical)
            "insmod", "rmmod", "modprobe"         // Kernel modules
    ));

    /**
     * Patterns for credential/sensitive data exposure
     */
    public static final Set<Pattern> SENSITIVE_PATTERNS = new HashSet<>(Arrays.asList(
            Pattern.compile(".*env\\s*$"),                                    // env
            Pattern.compile(".*printenv.*"),                                 // printenv
            Pattern.compile(".*cat\\s+~/.ssh.*"),                           // SSH keys
            Pattern.compile(".*cat\\s+~/.aws.*"),                           // AWS credentials
            Pattern.compile(".*cat\\s+/etc/passwd.*"),                      // System users
            Pattern.compile(".*cat\\s+/etc/shadow.*"),                      // Password hashes
            Pattern.compile(".*cat\\s+/root/.*"),                           // Root files
            Pattern.compile(".*cat\\s+.*\\.pem.*"),                         // Private keys
            Pattern.compile(".*cat\\s+.*\\.key.*"),                         // Private keys
            Pattern.compile(".*aws\\s+configure.*"),                        // AWS credentials
            Pattern.compile(".*export\\s+AWS_.*"),                          // AWS env vars
            Pattern.compile(".*export\\s+.*PASSWORD.*"),                    // Password env vars
            Pattern.compile(".*docker\\s+run\\s+.*--privileged.*"),        // Privileged containers
            Pattern.compile(".*curl\\s+.*http.*://.*"),                     // Network requests (potential exfil)
            Pattern.compile(".*wget\\s+.*http.*://.*")                      // Network requests (potential exfil)
    ));

    /**
     * Patterns for command injection attempts
     */
    public static final Set<Pattern> INJECTION_PATTERNS = new HashSet<>(Arrays.asList(
            Pattern.compile(".*;\\s*(?:rm|dd|mkfs|chmod)\\s+.*"),           // Command chaining with dangerous commands
            Pattern.compile(".*\\|\\s*(?:rm|dd|mkfs)\\s+.*"),               // Pipe to dangerous commands
            Pattern.compile(".*&&\\s*(?:rm|dd|mkfs)\\s+.*"),                // AND chaining with dangerous commands
            Pattern.compile(".*`.*(?:rm|dd|mkfs|sudo).*`.*"),               // Backtick command substitution with dangerous
            Pattern.compile(".*\\$\\(.*(?:rm|dd|mkfs|sudo).*\\).*"),        // $() command substitution with dangerous
            Pattern.compile(".*>\\s*/dev/.*"),                              // Redirect to device files
            Pattern.compile(".*<\\s*/dev/.*")                               // Read from device files
    ));
}
