package com.example.shortudy.global.config;

import com.example.shortudy.global.security.filter.JwtAuthenticationFilter;
import com.example.shortudy.global.security.JwtTokenProvider;
import com.example.shortudy.global.security.handler.CustomAccessDeniedHandler;
import com.example.shortudy.global.security.handler.CustomAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            JwtTokenProvider jwtTokenProvider,
            CustomAuthenticationEntryPoint authenticationEntryPoint,
            CustomAccessDeniedHandler accessDeniedHandler,
            @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173,https://shortudy.vercel.app}") String allowedOrigins
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
        CharacterEncodingFilter characterEncodingFilter = new CharacterEncodingFilter();
        characterEncodingFilter.setEncoding("UTF-8");
        characterEncodingFilter.setForceEncoding(true);

        http
                // 메소드 참조식 -> .csrf(csrf -> csrf.disable()) 해당 람다식과 동일
                // 서버에서 세션방법을 채택하지 않고 JWT 토큰으로 인증을 하기 때문에
                // 브라우저 쿠키를 가로채서 공격하는 CSRF 방어 기능은 꺼두겠다.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                // 세션을 만들지 않겠다(STATELESS) -> 서버가 사용자의 상태를 저장하지 않고, 오직 토큰만 보고 판단
                .sessionManagement(session -> {session.sessionCreationPolicy(SessionCreationPolicy.STATELESS);})
                // 요청 권한 제어 -> 누구에게 열어줄 것인가.
                .authorizeHttpRequests(auth -> auth
                        // .permitAll() -> 누구나 접근할 수 있는 권한 제어
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/users").permitAll()
                        .requestMatchers("/api/v1/recommendations/shorts/*").permitAll()

                        // .authenticated() -> 해당 요청은 인증이 필요하다
                        .requestMatchers(HttpMethod.GET, "/api/v1/shorts/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/shorts/*/upload-status").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/playlists/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/likes/shorts").authenticated()

                        // GET 요청의 특정 데이터 조회는 누구나 가능하다.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/shorts/**",
                                "/api/v1/categories/**",
                                "/api/v1/comments/**",
                                "/api/v1/keywords/**",
                                "/api/v1/playlists/**").permitAll()

                        // 아래 요청에는 ADMIN이라는 역할이 필요하다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/categories/**", "/api/v1/keywords/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**", "/api/v1/keywords/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/categories/**", "/api/v1/keywords/**").hasRole("ADMIN")

                        // 의외에 모든 요청에는 반드시 토큰 검증이 필요하다.
                        .anyRequest().authenticated()
                )
                // 시큐리티는 기본적으로 에러가 나면 '로그인 페이지'로 보내려고 한다. 이는 REST API 서버에 부적절함
                // 직접 만든 에러 응답을 내보내기 위함
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                // 시큐리티 기본 필터가 검사하기 전, 구현한 필터가 먼저 토큰을 검사해서 유저를 확인하기 위함
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(characterEncodingFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cache-Control", "Accept", "Origin"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
