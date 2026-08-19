package com.example.myGithubAction.execution_engine.executor;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.example.myGithubAction.execution_engine.constant.ExecutionEngineConstants;

/**
 * Validates bash commands to prevent execution of dangerous/sensitive commands.
 *
 * Responsible for:
 * - Blocking dangerous system commands (rm -rf, format, mkfs, etc.)
 * - Blocking credential exposure commands (env, printenv, cat ~/.ssh, etc.)
 * - Blocking privilege escalation attempts (sudo, su, chmod 777, etc.)
 * - Blocking network/connectivity isolation (iptables, firewall rules, etc.)
 * - Detecting command injection patterns
 * - Logging blocked commands for audit trail
 */
@Component
public class CommandValidator {

    /**
     * Validates if a command is safe to execute.
     *
     * @param command the bash command to validate
     * @return true if command is safe, false otherwise
     * @throws CommandValidationException if command is blocked with details
     */
    public boolean validateCommand(String command) throws CommandValidationException {
        if (command == null || command.trim().isEmpty()) {
            throw new CommandValidationException("Command cannot be null or empty");
        }

        String trimmedCommand = command.trim();

        // Check for forbidden commands
        checkForbiddenCommands(trimmedCommand);

        // Check for sensitive data exposure patterns
        checkSensitivePatterns(trimmedCommand);

        // Check for command injection attempts
        checkInjectionPatterns(trimmedCommand);

        return true;
    }

    /**
     * Checks if command contains forbidden commands.
     *
     * @param command the command to check
     * @throws CommandValidationException if forbidden command found
     */
    private void checkForbiddenCommands(String command) throws CommandValidationException {
        String[] tokens = command.split("\\s+|;|\\||&&|\\|\\||\\(|\\)");

        for (String token : tokens) {
            String cleanToken = token.replaceAll("^['\"]|['\"]$", "").toLowerCase();

            if (ExecutionEngineConstants.FORBIDDEN_COMMANDS.contains(cleanToken)) {
                // Special handling for commands that might be used safely
                if ("chmod".equalsIgnoreCase(cleanToken) || "chown".equalsIgnoreCase(cleanToken)) {
                    // Allow chmod/chown only if not using 777 or 000
                    if (command.contains("777") || command.contains("000") || command.contains("*")) {
                        throw new CommandValidationException(
                                String.format("Forbidden command detected: '%s' with dangerous permissions", cleanToken)
                        );
                    }
                } else if ("kill".equalsIgnoreCase(cleanToken) || "killall".equalsIgnoreCase(cleanToken)) {
                    // Allow kill only if not targeting system-critical processes
                    if (command.matches(".*kill.*\\s+(1|init|systemd|kernel).*")) {
                        throw new CommandValidationException(
                                "Forbidden: Cannot kill system-critical processes"
                        );
                    }
                } else if ("systemctl".equalsIgnoreCase(cleanToken) || "service".equalsIgnoreCase(cleanToken)) {
                    // Allow only safe systemctl operations
                    if (!command.matches(".*(?:status|is-active|is-enabled).*")) {
                        throw new CommandValidationException(
                                String.format("Forbidden systemctl operation: only status/is-active/is-enabled allowed")
                        );
                    }
                } else {
                    throw new CommandValidationException(
                            String.format("Forbidden command detected: '%s'", cleanToken)
                    );
                }
            }
        }
    }

    /**
     * Checks if command exposes sensitive data.
     *
     * @param command the command to check
     * @throws CommandValidationException if sensitive pattern found
     */
    private void checkSensitivePatterns(String command) throws CommandValidationException {
        for (Pattern pattern : ExecutionEngineConstants.SENSITIVE_PATTERNS) {
            if (pattern.matcher(command).matches()) {
                throw new CommandValidationException(
                        "Command blocked: Attempted to access or expose sensitive data (credentials, environment variables, or system files)"
                );
            }
        }
    }

    /**
     * Checks if command contains injection patterns.
     *
     * @param command the command to check
     * @throws CommandValidationException if injection pattern found
     */
    private void checkInjectionPatterns(String command) throws CommandValidationException {
        for (Pattern pattern : ExecutionEngineConstants.INJECTION_PATTERNS) {
            if (pattern.matcher(command).matches()) {
                throw new CommandValidationException(
                        "Command blocked: Potential command injection pattern detected"
                );
            }
        }
    }

    /**
     * Exception thrown when command validation fails.
     */
    public static class CommandValidationException extends Exception {
        public CommandValidationException(String message) {
            super(message);
        }

        public CommandValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
