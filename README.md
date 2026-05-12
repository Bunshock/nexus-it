# Note Generation App for IT Support Área
Continuation of `html-template-editor-to-print` repository, revamped for IT support area.

## Description:
During my time at the IT Support Office of Universidad Siglo 21 (Campus Branch), I noticed that the equipment registration process could be improved. Specifically, the generation of handover and return notes was prone to various failures because the process was entirely manual.

Driven by this, I decided to analyze the situation and take action. First, I developed an initial prototype. Its main goals were to streamline note generation, prevent typing errors (by implementing a mini-database in a `.json` file), and improve and standardize the design using `.html` templates. The app was deployed locally on users' PCs, utilizing a file server for shared resources such as templates, profiles, and the database.

After several months of use, I found that errors were still being made. While inconsistencies in certain data points had been largely eliminated, errors in sensitive fields persisted.

With the implementation of GLPI, I saw an opportunity to create a new application based on what I had learned. This new version aims to improve robustness by utilizing both GLPI and Active Directory (AD) for hardware and user data validation. This version is currently under development, with high expectations and a clear vision for the future: fast and reliable traceability.
