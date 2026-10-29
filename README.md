# ExamFlow — Java backend

A local examination management application with a **Java 21 backend**, an HTML/CSS/JavaScript interface and a **SQLite database accessed through JDBC**. Node.js is not required.

## Start

1. Double-click **Start ExamFlow.cmd**.
2. Open **http://127.0.0.1:3030**.
3. Keep the server window open while using the application. Press Ctrl+C to stop it.

The launcher finds Java 21+ through JAVA_HOME, PATH, common installation folders or the Java extension for VS Code. Java 21 is already available through VS Code on this computer. Other computers need Java 21 or later.

For administrator access, open **http://127.0.0.1:3030/#admin-login**, or choose **Administrator sign in** on the home page. Double-click **Open Admin Access.cmd** in the project folder to view your saved administrator email and password. Administrator credentials are in **data/admin-access.txt**. A unique password is generated on the first launch of a fresh installation. Students register from the home page. Existing accounts use their existing passwords.

## Features

- Home, shared sign-in and student registration pages
- Student and administrator dashboards
- Exam search and subject filters
- Timed multiple-choice exams, saved answers and question navigation
- Server-side scoring and one attempt per student/exam
- Automatic submission at the deadline while the server runs, even with the browser closed
- Results and answer review after submission
- Administrator exam creation, publication controls and student directory
- Sample Java, Electrical Fundamentals and Database Essentials exams
- Password hashing, HTTP-only session cookies and server-enforced roles

Each correct answer earns one mark with no negative marking. Questions remain fixed to preserve historical results. Create a new exam to revise questions. Unpublishing prevents new starts; an existing attempt remains accessible through its original attempt URL.

## Java implementation

| File | Responsibility |
| --- | --- |
| src/main/java/examflow/ExamFlow.java | HTTP routes, roles, exams, scoring and deadline scheduler |
| src/main/java/examflow/Database.java | JDBC database and prepared statements |
| src/main/java/examflow/Passwords.java | PBKDF2 passwords and original scrypt compatibility |
| public/ | Browser interface |
| config/sample-exams.json | First-launch sample questions |
| lib/ | Included libraries and Eclipse Java compiler |
| build/ExamFlow-2.1.jar | Compiled application |
| pom.xml | Maven definition for IDE import |

The server uses Java's built-in HTTP server, SQLite JDBC, Gson and Bouncy Castle. Double-click **Build Java.cmd** after changing Java sources. It uses the included Eclipse compiler and the installed JDK's jar tool. No npm or Maven installation is required to launch. You can import pom.xml into IntelliJ IDEA, Eclipse or VS Code.

## Data preservation

The Java backend opens the same **data/examflow.sqlite** database as the earlier version. Accounts, questions, attempts, results and unexpired sessions remain compatible. Original scrypt passwords work and upgrade to PBKDF2 after successful login. No password reset is needed.

The downloadable ZIP excludes private accounts, credentials, sessions and results. An extracted copy creates a fresh database. To transfer an existing installation, stop the server and copy its **data/** folder privately. Back up this folder before upgrades.

## Tests

Set JAVA_EXE to Java 21 and run `python tests/test_java_backend.py` using Python 3. The isolated test checks authentication, permissions, scoring, simultaneous submissions, answer privacy, restart persistence, deadlines, old password/session compatibility, publishing and logout. It never uses the real data directory.

`GET /api/health` identifies the running backend as Java.

## Local scope

The application listens only on 127.0.0.1. It has no public hosting, email verification, password recovery, proctoring or scheduled exam windows. An institutional deployment needs HTTPS hosting, backups and institution-specific exam policies.

**SQLite remains the database to preserve the current installation. MySQL from the presentation has not been implemented.**

Advanced settings: PORT changes the port, EXAMFLOW_DATA changes the data folder, and -Dexamflow.root changes the project directory.

## Restricted administrator creation

Sign in as an administrator, then choose **Administrators** in the sidebar. Enter the new administrator’s name, email and password, confirm the new password, enter your own current administrator password, and accept the displayed privacy and access conditions.

Only existing administrators can use this form. Public registration always creates student accounts. An existing student account cannot be promoted through this form. New administrator passwords require at least 12 characters. The creator remains signed in. Passwords are hashed and omitted from all account lists. An audit record stores the creator, new account, time and access-conditions version. Five incorrect current-password confirmations block further confirmation attempts for 10 minutes.

**After updating an already running installation:** double-click **Restart ExamFlow.cmd** once to load the new Java backend. It verifies that the process on the configured port is ExamFlow before stopping it. Starting the app uses `build/ExamFlow-2.1.jar`. The database and existing accounts are preserved.
