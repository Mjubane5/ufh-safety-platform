package za.ac.ufh.safety.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Student number is required.")
    @Pattern(regexp = "^[0-9]{6,12}$", message = "Student number must contain 6 to 12 digits.")
    String studentNumber,

    @NotBlank(message = "Full name is required.")
    @Size(max = 120, message = "Full name is too long.")
    String fullName,

    @NotBlank(message = "Email is required.")
    @Email(message = "Enter a valid email address.")
    @Size(max = 160, message = "Email is too long.")
    String email,

    // Two rules rather than one @Size, so a very long password is not told it
    // is too short. The 72 is BCrypt's limit: it ignores anything past 72 bytes,
    // and silently accepting a longer one would mean only the first 72 count.
    @NotBlank(message = "Password is required.")
    @Size(min = 8, message = "Password must be at least 8 characters.")
    @Size(max = 72, message = "Password must be 72 characters or fewer.")
    String password,

    @Size(max = 30, message = "Phone number is too long.")
    String phone
) {}
