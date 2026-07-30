package com.shizuku.translate.service;

import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.UserRepository;
import com.shizuku.translate.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    public void register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new RuntimeException("Username already exists");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new RuntimeException("Email already registered");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);
    }

    public String login(LoginRequest request) {
        String username = request.getUsername().trim();
        User user;

        if (username.contains("@")) {
            user = userRepository.findByEmailIgnoreCase(username)
                    .orElseThrow(() -> new RuntimeException("Invalid username or password"));
        } else {
            user = userRepository.findByUsernameIgnoreCase(username)
                    .orElseThrow(() -> new RuntimeException("Invalid username or password"));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Invalid username or password");
        }
        return tokenProvider.generateToken(user.getUsername());
    }

    public User findByUsername(String username) {
        return userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    public long getUserCount() {
        return userRepository.count();
    }

}
