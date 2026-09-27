package br.com.fiap.fiapx.video.infrastructure.web;
import br.com.fiap.fiapx.video.core.exception.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestControllerAdvice
public class VideoApiErrors {
    @ExceptionHandler(VideoAccessException.class)
    public ResponseEntity<ProblemDetail> access(VideoAccessException exception) {
        return response(switch (exception.reason()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        });
    }
    @ExceptionHandler(VideoNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(VideoNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND);
    }
    @ExceptionHandler(VideoPersistenceException.class)
    public ResponseEntity<ProblemDetail> persistence(VideoPersistenceException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> invalid(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST);
    }
    private ResponseEntity<ProblemDetail> response(HttpStatus status) {
        var builder = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return builder.body(ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase()));
    }
}
