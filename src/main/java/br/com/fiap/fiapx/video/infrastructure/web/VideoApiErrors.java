package br.com.fiap.fiapx.video.infrastructure.web;
import br.com.fiap.fiapx.video.core.exception.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestControllerAdvice
public class VideoApiErrors {
    @ExceptionHandler(DownloadException.class)
    public ResponseEntity<ProblemDetail> download(DownloadException exception) {
        var status = switch (exception.reason()) {
            case NOT_READY -> HttpStatus.CONFLICT;
            case EXPIRED -> HttpStatus.GONE;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
        problem.setProperty("code", "DOWNLOAD_" + exception.reason().name());
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(problem);
    }
    @ExceptionHandler(UploadException.class)
    public ResponseEntity<ProblemDetail> upload(UploadException exception) {
        var status = switch (exception.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case CONTENT_CONFLICT, IN_PROGRESS -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
        problem.setProperty("code", "UPLOAD_" + exception.reason().name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(Exception exception) {
        return upload(new UploadException(UploadException.Reason.TOO_LARGE));
    }

    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ProblemDetail> malformedMultipart(Exception exception) {
        return upload(new UploadException(UploadException.Reason.INVALID));
    }

    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            jakarta.servlet.ServletException.class})
    public ResponseEntity<ProblemDetail> malformedUpload(Exception exception) {
        return upload(new UploadException(UploadException.Reason.INVALID));
    }

    @ExceptionHandler(java.io.IOException.class)
    public ResponseEntity<ProblemDetail> uploadReadFailure(Exception exception) {
        return upload(new UploadException(UploadException.Reason.UNAVAILABLE));
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMedia(Exception exception) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
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
        var problem = ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
        problem.setProperty("code", status.name());
        return builder.body(problem);
    }
}
