package ca.feedline.web;

public class UnknownQueryParameterException extends RuntimeException {
    private final String parameter;

    public UnknownQueryParameterException(String parameter) {
        super("Unknown query parameter: " + parameter);
        this.parameter = parameter;
    }

    public String getParameter() {
        return parameter;
    }
}
