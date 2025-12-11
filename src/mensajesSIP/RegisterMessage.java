/*
 * Codigo de base para parsear mensajes SIP
 * Puede ser adaptado, ampliado, modificado por el alumno
 * segun sus necesidades para la practica
 */
package mensajesSIP;

import java.util.ArrayList;

/**
 * Clase que representa un mensaje SIP de tipo REGISTER
 * 
 * @author SMA
 */
public class RegisterMessage extends SIPMessage {

    public String destination;
    public int maxForwards;
    public String contact;
    private String authorization;
    public int expires;
    public int contentLength;

    private long expirationDeadlineMillis = 0L;

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public int getMaxForwards() {
        return maxForwards;
    }

    public void setMaxForwards(int maxForwards) {
        this.maxForwards = maxForwards;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public int getExpires() {
        return expires;
    }

    public synchronized void setExpires(int expires) {
        this.expires = expires;
        this.expirationDeadlineMillis = System.currentTimeMillis() + expires * 1000L;
    }

    public String getAuthorization() {
        return authorization;
    }

    public void setAuthorization(String authorization) {
        this.authorization = authorization;
    }

    public int getContentLength() {
        return contentLength;
    }

    public void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public ArrayList<String> getVias() {
        return vias;
    }

    public void setVias(ArrayList<String> vias) {
        this.vias = vias;
    }

    public String getToName() {
        return toName;
    }

    public void setToName(String toName) {
        this.toName = toName;
    }

    public String getToUri() {
        return toUri;
    }

    public void setToUri(String toUri) {
        this.toUri = toUri;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getFromUri() {
        return fromUri;
    }

    public void setFromUri(String fromUri) {
        this.fromUri = fromUri;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getcSeqNumber() {
        return cSeqNumber;
    }

    public void setcSeqNumber(String cSeqNumber) {
        this.cSeqNumber = cSeqNumber;
    }

    public String getcSeqStr() {
        return cSeqStr;
    }

    public void setcSeqStr(String cSeqStr) {
        this.cSeqStr = cSeqStr;
    }

    public synchronized boolean isExpired() {
        return expires > 0 && System.currentTimeMillis() >= expirationDeadlineMillis;
    }

    public synchronized long getRemainingLifetimeMillis() {
        if (expires <= 0) {
            return 0L;
        }
        long remaining = expirationDeadlineMillis - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    @Override
    public String toStringMessage() {
        String register;
        register = "REGISTER " + destination + " SIP/2.0\n";
        for (int i=0; i<vias.size(); i++) {
            register += "Via: SIP/2.0/UDP " + vias.get(i) + "\n";
        }
        register += "Max-Forwards: " + maxForwards + "\n";
        if(getToName()!=null)
            register += "To: " + getToName() + " <" + toUri + ">\n";
        else
            register += "To: <" + toUri + ">\n";
        if(fromName!=null)
            register += "From: " + fromName + " <" + fromUri + ">\n";
        else
            register += "From: <" + fromUri + ">\n";
        register += "Call-ID: " + callId + "\n";
        register += "CSeq: " + cSeqNumber + " " + cSeqStr + "\n";
        register += "Contact: <sip:" + contact + ">\n";
        if(getAuthorization()!=null)
        	register += "Authorization: response= " + authorization + "\n";
        register += "Expires: " + expires + "\n";
        register += "Content-Length: " + contentLength + "\n";
        register += "\n";

        return register;
    }

    public OKMessage createOKResponse() {
        OKMessage okMessage = new OKMessage(
            this.vias,
            this.toName,
            this.toUri,
            this.fromName,
            this.fromUri,
            this.callId,
            nextCSeq(),
            this.cSeqStr
        );
        okMessage.setContact(this.contact);
        okMessage.setExpires(String.valueOf(this.expires));
        okMessage.setContentLength(0);
        return okMessage;
    }

    public NotFoundMessage createNotFoundResponse() {
        NotFoundMessage notFoundMessage = new NotFoundMessage(
            this.vias,
            this.toName,
            this.toUri,
            this.fromName,
            this.fromUri,
            this.callId,
            nextCSeq(),
            this.cSeqStr
        );
        notFoundMessage.setContact(this.contact);
        notFoundMessage.setContentLength(0);
        return notFoundMessage;
    }
}
