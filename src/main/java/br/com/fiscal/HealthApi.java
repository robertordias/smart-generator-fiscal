package br.com.fiscal;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthApi {
    private final JdbcTemplate db;
    HealthApi(JdbcTemplate db){this.db=db;}
    @GetMapping("/health") ResponseEntity<Map<String,String>> health(){
        try {
            db.queryForObject("SELECT 1",Integer.class);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status","UP"));
        } catch (org.springframework.dao.DataAccessException ex) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("status","DOWN"));
        }
    }
}
