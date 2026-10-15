# Security policy

## Reporting a vulnerability

Report a security issue in Kyber privately:

https://github.com/infoware-se/kyber/security/advisories/new

Do not open a public GitHub issue for a security report.

Kyber is a KEEP extension. A design call runs as the authenticated Domino REST user and is limited by that user's access on the target NSF. A report should describe how a caller can do more than that user is allowed to do.

Include the Kyber version, the API call, and the access level of the user. Do not include passwords, tokens, or a live server name.
