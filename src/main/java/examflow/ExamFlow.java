package examflow;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Java HTTP application serving the existing responsive frontend and JSON API. */
public final class ExamFlow {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Path root, data;
    private final Database db;
    private final Map<String,LoginFailures> failures=new HashMap<>();
    private final Map<Long,LoginFailures> adminConfirmFailures=new HashMap<>();
    private final Object lock=new Object();
    private static final class LoginFailures { int count; long until=System.currentTimeMillis()+600000; }
    private static final class ApiError extends RuntimeException {
        private static final long serialVersionUID=1L;
        final int status;
        ApiError(int status,String message) { super(message);this.status=status; }
    }
    private record Response(int status,Object data) {}
    private static ApiError error(int status,String text) { return new ApiError(status,text); }
    private static Map<String,Object> map(Object... values) {
        Map<String,Object> m=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);
        return m;
    }
    private static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private static boolean done(Map<String,Object> a) { return a.get("submitted")!=null; }
    private static String clean(JsonObject body,String key,int max) {
        JsonElement value=body.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw error(400,"Please check the required fields.");
        String s=value.getAsString().trim();
        if(s.isEmpty()||s.length()>max)throw error(400,"Please check the required fields.");
        return s;
    }
    private static int integer(JsonElement value,int min,int max,String message) {
        try {
            if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException();
            int n=value.getAsBigDecimal().intValueExact();
            if(n<min||n>max)throw new IllegalArgumentException();
            return n;
        }catch(Exception e){throw error(400,message);}
    }
    private static boolean published(JsonObject b) {
        if(!b.has("published"))return false;
        JsonElement e=b.get("published");
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw error(400,"Invalid publication status.");
        return e.getAsBoolean();
    }
    private static String cookie(HttpExchange x) {
        String value=x.getRequestHeaders().getFirst("Cookie");
        if(value!=null)for(String part:value.split(";"))if(part.trim().startsWith("examflow="))return part.trim().substring(9);
        return "";
    }
    private Map<String,Object> user(HttpExchange x)throws Exception {
        return db.one("SELECT u.id,u.name,u.email,u.role FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token=? AND s.expires>?",cookie(x),System.currentTimeMillis());
    }
    private Map<String,Object> auth(HttpExchange x,String role)throws Exception {
        Map<String,Object> u=user(x);
        if(u==null)throw error(401,"Please sign in to continue.");
        if(role!=null&&!role.equals(u.get("role")))throw error(403,"You do not have access to this action.");
        return u;
    }
    private List<Map<String,Object>> questions(long id,boolean reveal)throws Exception {
        List<Map<String,Object>> qs=db.all("SELECT id,prompt,options,answer FROM questions WHERE exam_id=? ORDER BY id",id);
        for(Map<String,Object> q:qs){q.put("options",JsonParser.parseString((String)q.get("options")));if(!reveal)q.remove("answer");}
        return qs;
    }
    private Map<String,Object> finish(Map<String,Object> a)throws Exception {
        if(done(a))return a;
        JsonObject answers=JsonParser.parseString((String)a.get("answers")).getAsJsonObject();int score=0;
        for(Map<String,Object> q:questions(number(a,"exam_id"),true)){
            JsonElement v=answers.get(String.valueOf(number(q,"id")));
            if(v!=null&&v.getAsInt()==number(q,"answer"))score++;
        }
        db.update("UPDATE attempts SET submitted=?,score=? WHERE id=? AND submitted IS NULL",System.currentTimeMillis(),score,a.get("id"));
        return db.one("SELECT * FROM attempts WHERE id=?",a.get("id"));
    }
    private void expire()throws Exception {
        for(Map<String,Object> a:db.all("SELECT * FROM attempts WHERE submitted IS NULL AND deadline<=?",System.currentTimeMillis()))finish(a);
    }
    private static JsonObject body(HttpExchange x)throws IOException {
        byte[] bytes=x.getRequestBody().readNBytes(150001);
        if(bytes.length>150000)throw error(413,"This request is too large.");
        try { return bytes.length==0?new JsonObject():JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch(Exception e){throw error(400,"Invalid request.");}
    }
    private ExamFlow(Path root,Path data)throws Exception {this.root=root;this.data=data;db=new Database(data);seed();}
    private void seed()throws Exception {
        if(db.one("SELECT id FROM users LIMIT 1")!=null)return;
        String password=Passwords.token(12);
        db.begin();
        try {
            db.insert("INSERT INTO users(name,email,password,role) VALUES(?,?,?,?)","Exam Administrator","admin@examflow.local",Passwords.hash(password),"admin");
            JsonArray samples=JsonParser.parseString(Files.readString(root.resolve("config/sample-exams.json"))).getAsJsonArray();
            for(JsonElement sample:samples){
                JsonArray e=sample.getAsJsonArray();
                long id=db.insert("INSERT INTO exams(title,subject,description,duration,published) VALUES(?,?,?,?,1)",e.get(0).getAsString(),e.get(1).getAsString(),e.get(2).getAsString(),e.get(3).getAsInt());
                for(JsonElement element:e.get(4).getAsJsonArray()){
                    JsonArray q=element.getAsJsonArray();
                    db.insert("INSERT INTO questions(exam_id,prompt,options,answer) VALUES(?,?,?,?)",id,q.get(0).getAsString(),JSON.toJson(q.get(1)),q.get(2).getAsInt());
                }
            }
            Files.writeString(data.resolve("admin-access.txt"),"Administrator email: admin@examflow.local\nAdministrator password: "+password+"\nKeep this file private.\n");
            db.commit();
        }catch(Exception e){db.rollback();throw e;}
    }
    private Response api(HttpExchange x)throws Exception {
        String method=x.getRequestMethod(),p=x.getRequestURI().getPath();JsonObject b=new JsonObject();
        if(Set.of("POST","PUT","DELETE").contains(method)){
            String origin=x.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!origin.equals("http://"+x.getRequestHeaders().getFirst("Host")))throw error(403,"Request origin is not allowed.");
            b=body(x);
        }
        expire();
        if(p.equals("/api/me")&&method.equals("GET"))return new Response(200,map("user",user(x)));
        if(p.equals("/api/health")&&method.equals("GET"))return new Response(200,map("status","ok","backend","Java","version",System.getProperty("java.version"),"release","2.1-admin-accounts"));
        if(p.equals("/api/admin/accounts")&&method.equals("GET")){
            auth(x,"admin");
            return new Response(200,map("accounts",db.all("SELECT u.id,u.name,u.email,e.created_at,actor.name created_by FROM users u LEFT JOIN admin_account_events e ON e.created_user_id=u.id LEFT JOIN users actor ON actor.id=e.actor_id WHERE u.role='admin' ORDER BY u.id")));
        }
        if(p.equals("/api/admin/accounts")&&method.equals("POST")){
            Map<String,Object> actor=auth(x,"admin");
            long actorId=number(actor,"id");
            LoginFailures f=adminConfirmFailures.computeIfAbsent(actorId,k->new LoginFailures());
            if(f.until<System.currentTimeMillis()){f.count=0;f.until=System.currentTimeMillis()+600000;}
            if(f.count>=5)throw error(429,"Too many password confirmation attempts. Try again in 10 minutes.");
            String currentPassword=clean(b,"currentPassword",128);
            Map<String,Object> stored=db.one("SELECT password FROM users WHERE id=?",actorId);
            if(!Passwords.verify(currentPassword,(String)stored.get("password"))){f.count++;throw error(403,"Your current administrator password is incorrect.");}
            adminConfirmFailures.remove(actorId);
            if(!b.has("acceptConditions")||!b.get("acceptConditions").isJsonPrimitive()||!b.get("acceptConditions").getAsJsonPrimitive().isBoolean()||!b.get("acceptConditions").getAsBoolean())throw error(400,"Confirm the administrator access conditions before creating the account.");
            String name=clean(b,"name",80),email=clean(b,"email",120).toLowerCase(Locale.ROOT),password=clean(b,"password",128),confirmation=clean(b,"confirmPassword",128);
            if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw error(400,"Enter a valid email address.");
            if(password.length()<12)throw error(400,"Use at least 12 characters for the new administrator password.");
            if(!password.equals(confirmation))throw error(400,"The new passwords do not match.");
            if(db.one("SELECT id FROM users WHERE email=?",email)!=null)throw error(409,"An account already exists with this email. Existing student accounts cannot be promoted through this form.");
            db.begin();try{
                long id=db.insert("INSERT INTO users(name,email,password,role) VALUES(?,?,?,?)",name,email,Passwords.hash(password),"admin");
                db.insert("INSERT INTO admin_account_events(actor_id,created_user_id,created_at,conditions_version) VALUES(?,?,?,?)",actorId,id,System.currentTimeMillis(),"admin-access-v1");
                db.commit();
                return new Response(201,map("account",map("id",id,"name",name,"email",email,"role","admin")));
            }catch(Exception e){db.rollback();throw e;}
        }
        if(p.equals("/api/register")&&method.equals("POST")){
            String name=clean(b,"name",80),email=clean(b,"email",120).toLowerCase(Locale.ROOT),password=clean(b,"password",128);
            if(!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))throw error(400,"Enter a valid email address.");
            if(password.length()<10)throw error(400,"Use a password with at least 10 characters.");
            if(db.one("SELECT id FROM users WHERE email=?",email)!=null)throw error(409,"An account already exists with this email.");
            db.insert("INSERT INTO users(name,email,password,role) VALUES(?,?,?,?)",name,email,Passwords.hash(password),"student");
            return new Response(201,map("ok",true));
        }
        if(p.equals("/api/login")&&method.equals("POST")){
            String key=x.getRemoteAddress().getAddress().getHostAddress();
            LoginFailures f=failures.computeIfAbsent(key,k->new LoginFailures());
            if(f.until<System.currentTimeMillis()){f.count=0;f.until=System.currentTimeMillis()+600000;}
            if(f.count>=15)throw error(429,"Too many attempts. Try again in 10 minutes.");
            String email=clean(b,"email",120).toLowerCase(Locale.ROOT),password=clean(b,"password",128);
            Map<String,Object> u=db.one("SELECT * FROM users WHERE email=?",email);
            if(u==null||!Passwords.verify(password,(String)u.get("password"))){f.count++;throw error(401,"Email or password is incorrect.");}
            failures.remove(key);
            if(!((String)u.get("password")).startsWith("pbkdf2:"))db.update("UPDATE users SET password=? WHERE id=?",Passwords.hash(password),u.get("id"));
            String token=Passwords.token(32);
            db.update("DELETE FROM sessions WHERE expires<?",System.currentTimeMillis());
            db.insert("INSERT INTO sessions(token,user_id,expires) VALUES(?,?,?)",token,u.get("id"),System.currentTimeMillis()+28800000);
            x.getResponseHeaders().set("Set-Cookie","examflow="+token+"; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800");
            u.remove("password");return new Response(200,map("user",u));
        }
        if(p.equals("/api/logout")&&method.equals("POST")){
            db.update("DELETE FROM sessions WHERE token=?",cookie(x));
            x.getResponseHeaders().set("Set-Cookie","examflow=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0");
            return new Response(200,map("ok",true));
        }
        if(p.equals("/api/dashboard")&&method.equals("GET")){
            Map<String,Object> u=auth(x,null);boolean admin=u.get("role").equals("admin");
            Object exams=db.all("SELECT e.*,COUNT(q.id) question_count FROM exams e LEFT JOIN questions q ON q.exam_id=e.id "+(admin?"":"WHERE e.published=1 ")+"GROUP BY e.id ORDER BY e.id");
            Object attempts=db.all("SELECT a.*,e.title,u.name,u.email FROM attempts a JOIN exams e ON e.id=a.exam_id JOIN users u ON u.id=a.user_id "+(admin?"":"WHERE a.user_id=? ")+"ORDER BY a.started DESC",admin?new Object[0]:new Object[]{u.get("id")});
            return new Response(200,map("exams",exams,"attempts",attempts,"students",admin?db.all("SELECT id,name,email FROM users WHERE role='student' ORDER BY id DESC"):List.of()));
        }
        if(p.equals("/api/exams")&&method.equals("POST")){
            auth(x,"admin");String title=clean(b,"title",100),subject=clean(b,"subject",80),description=clean(b,"description",600);
            int duration=integer(b.get("duration"),1,180,"Duration must be between 1 and 180 minutes.");
            boolean publish=published(b);
            if(!b.has("questions")||!b.get("questions").isJsonArray())throw error(400,"Add between 1 and 100 questions.");
            JsonArray qs=b.getAsJsonArray("questions");
            if(qs.isEmpty()||qs.size()>100)throw error(400,"Add between 1 and 100 questions.");
            List<Map<String,Object>> valid=new ArrayList<>();
            for(JsonElement el:qs){
                if(!el.isJsonObject())throw error(400,"Invalid question.");JsonObject q=el.getAsJsonObject();
                String prompt=clean(q,"prompt",1000);
                if(!q.has("options")||!q.get("options").isJsonArray()||q.getAsJsonArray("options").size()!=4)throw error(400,"Each question needs four choices.");
                List<String> opts=new ArrayList<>();for(JsonElement o:q.getAsJsonArray("options")){JsonObject wrapper=new JsonObject();wrapper.add("value",o);opts.add(clean(wrapper,"value",400));}
                valid.add(map("prompt",prompt,"options",JSON.toJson(opts),"answer",integer(q.get("answer"),0,3,"Select the correct answer.")));
            }
            db.begin();try {
                long id=db.insert("INSERT INTO exams(title,subject,description,duration,published) VALUES(?,?,?,?,?)",title,subject,description,duration,publish?1:0);
                for(Map<String,Object> q:valid)db.insert("INSERT INTO questions(exam_id,prompt,options,answer) VALUES(?,?,?,?)",id,q.get("prompt"),q.get("options"),q.get("answer"));
                db.commit();return new Response(201,map("id",id));
            }catch(Exception e){db.rollback();throw e;}
        }
        Matcher m=Pattern.compile("^/api/exams/(\\d+)/(visibility|start)$").matcher(p);
        if(m.matches()&&method.equals("POST")){
            long id=Long.parseLong(m.group(1));
            if(m.group(2).equals("visibility")){
                auth(x,"admin");if(db.update("UPDATE exams SET published=? WHERE id=?",published(b)?1:0,id)==0)throw error(404,"Exam not found.");
                return new Response(200,map("ok",true));
            }
            Map<String,Object> u=auth(x,"student"),exam=db.one("SELECT * FROM exams WHERE id=? AND published=1",id);
            if(exam==null)throw error(404,"Exam is not available.");
            Map<String,Object> a=db.one("SELECT * FROM attempts WHERE user_id=? AND exam_id=?",u.get("id"),id);
            if(a==null){int total=questions(id,false).size();if(total==0)throw error(400,"This exam has no questions.");long now=System.currentTimeMillis();long aid=db.insert("INSERT INTO attempts(user_id,exam_id,started,deadline,total) VALUES(?,?,?,?,?)",u.get("id"),id,now,now+number(exam,"duration")*60000,total);a=db.one("SELECT * FROM attempts WHERE id=?",aid);}
            return new Response(200,map("attempt",a,"exam",exam,"questions",questions(id,false),"serverTime",System.currentTimeMillis()));
        }
        m=Pattern.compile("^/api/attempts/(\\d+)(?:/(save|submit))?$").matcher(p);
        if(m.matches()){
            String action=m.group(2);long id=Long.parseLong(m.group(1));
            if(action==null&&method.equals("GET")){
                Map<String,Object> u=auth(x,null),a=db.one("SELECT * FROM attempts WHERE id=?",id);
                if(a==null||(!u.get("role").equals("admin")&&number(a,"user_id")!=number(u,"id")))throw error(404,"Attempt not found.");
                return new Response(200,map("attempt",a,"exam",db.one("SELECT * FROM exams WHERE id=?",a.get("exam_id")),"questions",questions(number(a,"exam_id"),done(a)),"serverTime",System.currentTimeMillis()));
            }
            if(action!=null&&method.equals("POST")){
                Map<String,Object> u=auth(x,"student"),a=db.one("SELECT * FROM attempts WHERE id=? AND user_id=?",id,u.get("id"));
                if(a==null)throw error(404,"Attempt not found.");if(done(a))return new Response(200,map("attempt",a));
                if(!b.has("answers")||!b.get("answers").isJsonObject())throw error(400,"Invalid answers.");
                Set<String> allowed=new HashSet<>();for(Map<String,Object> q:questions(number(a,"exam_id"),false))allowed.add(String.valueOf(number(q,"id")));
                JsonObject answers=b.getAsJsonObject("answers");
                for(var entry:answers.entrySet()){
                    if(!allowed.contains(entry.getKey()))throw error(400,"Invalid answer choice.");integer(entry.getValue(),0,3,"Invalid answer choice.");
                }
                db.update("UPDATE attempts SET answers=? WHERE id=?",JSON.toJson(answers),id);a=db.one("SELECT * FROM attempts WHERE id=?",id);
                if(action.equals("submit"))a=finish(a);return new Response(200,map("attempt",a));
            }
        }
        throw error(404,"Not found.");
    }
    private static void send(HttpExchange x,int status,Object data)throws IOException {
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");byte[] bytes=JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);
    }
    private void handle(HttpExchange x)throws IOException {
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("X-Frame-Options","DENY");x.getResponseHeaders().set("Referrer-Policy","same-origin");
        x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        try {
            String p=x.getRequestURI().getPath();
            if(p.startsWith("/api/")){synchronized(lock){Response r=api(x);send(x,r.status,r.data);}return;}
            if(!x.getRequestMethod().equals("GET"))throw error(405,"Method not allowed.");
            String name=Map.of("/","index.html","/app.js","app.js","/style.css","style.css","/favicon.svg","favicon.svg").get(p);
            if(name==null)throw error(404,"Page not found.");
            byte[] bytes=Files.readAllBytes(root.resolve("public").resolve(name));
            x.getResponseHeaders().set("Content-Type",name.endsWith(".js")?"text/javascript; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":name.endsWith(".svg")?"image/svg+xml":"text/html; charset=utf-8");
            x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
        }catch(ApiError e){send(x,e.status,map("error",e.getMessage()));}
        catch(Exception e){e.printStackTrace();send(x,500,map("error","Something went wrong. Please try again."));}
        finally{x.close();}
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(System.getProperty("examflow.root",".")).toAbsolutePath().normalize();
        Path data=Path.of(System.getenv().getOrDefault("EXAMFLOW_DATA",root.resolve("data").toString()));
        ExamFlow app=new ExamFlow(root,data);int port=Integer.parseInt(System.getenv().getOrDefault("PORT","3030"));
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),50);
        server.createContext("/",app::handle);ExecutorService workers=Executors.newFixedThreadPool(8);server.setExecutor(workers);
        ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
        deadlines.scheduleAtFixedRate(()->{synchronized(app.lock){try{app.expire();}catch(Exception e){e.printStackTrace();}}},1,1,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(1);deadlines.shutdownNow();workers.shutdownNow();synchronized(app.lock){try{app.db.close();}catch(Exception ignored){}}}));
        server.start();System.out.println("ExamFlow Java is ready at http://127.0.0.1:"+port);
        System.out.println("Administrator access: "+data.resolve("admin-access.txt"));
    }
}
