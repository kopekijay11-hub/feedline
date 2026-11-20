package ca.feedline.service;

public class ItemIngestFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String guid;
    private final String failureClass;

    public ItemIngestFailure(String guid, String failureClass, Throwable cause) {
        super(failureClass + " while ingesting guid " + guid, cause);
        this.guid = guid;
        this.failureClass = failureClass;
    }

    public String getGuid() {
        return guid;
    }

    public String getFailureClass() {
        return failureClass;
    }
}
