package br.com.fiscal;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice(assignableTypes={NfseApi.class})
class IntegrationErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> business(ResponseStatusException ex){return ResponseEntity.status(ex.getStatusCode()).body(Map.of("error",ex.getReason()==null?"Recurso não encontrado":ex.getReason()));}
    @ExceptionHandler(MethodArgumentNotValidException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> invalid(MethodArgumentNotValidException ex){return Map.of("error","Revise os campos obrigatórios: "+ex.getBindingResult().getFieldErrors().stream().map(e->e.getField()).distinct().collect(java.util.stream.Collectors.joining(", ")));}
    @ExceptionHandler(DataIntegrityViolationException.class) @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> conflict(){return Map.of("error","Uma configuração ou emissão com essa identificação já existe. Atualize a consulta");}
}
