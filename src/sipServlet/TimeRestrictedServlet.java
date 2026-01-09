package sipServlet;

import java.time.LocalTime;

public class TimeRestrictedServlet implements SIPServletInterface {

    private static final String MY_USER = "mario"; 
    private static final String BOSS_USER = "boss";

    @Override
    public void doInvite(SipServletRequestInterface request) {
        String caller = request.getCallerURI(); 
        String callee = request.getCalleeURI(); 
        
        LocalTime now = LocalTime.now();
        int hour = now.getHour();
        
        System.out.println("TimeRestrictedServlet: Current hour is " + hour);

        if (callee.equalsIgnoreCase(MY_USER)) {
            // Incoming call to Mario
            // Rule: 9-17 AND caller is "boss"
            if (hour >= 9 && hour < 17) {
                if (caller.equalsIgnoreCase(BOSS_USER)) {
                    // Allow
                    System.out.println("TimeRestrictedServlet: Incoming call allowed from " + caller);
                    request.getProxy().proxyTo("sip:" + callee + "@it.uc3m.es"); 
                } else {
                    // Reject - Not boss
                    System.out.println("TimeRestrictedServlet: Incoming call rejected from " + caller + " (not boss)");
                    request.createResponse(403).send(); 
                }
            } else {
                // Reject - Out of time
                System.out.println("TimeRestrictedServlet: Incoming call rejected (out of time)");
                request.createResponse(486).send(); 
            }
        } else if (caller.equalsIgnoreCase(MY_USER)) {
            // Outgoing call from Mario
            // Rule: 10-11
            if (hour >= 10 && hour < 11) {
                // Allow
                System.out.println("TimeRestrictedServlet: Outgoing call allowed to " + callee);
                request.getProxy().proxyTo("sip:" + callee + "@it.uc3m.es");
            } else {
                // Reject
                System.out.println("TimeRestrictedServlet: Outgoing call rejected (out of time)");
                request.createResponse(403).send();
            }
        } else {
            // Fallback
            request.createResponse(503).send();
        }
    }
}
