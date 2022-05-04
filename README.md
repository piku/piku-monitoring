A basic load monitoring and diagnosis tool for [Piku](https://piku.github.io).

![Screencast of Piku Monitoring in action](./piku-monitor.gif)

# Deploy

Deploy Piku Monitoring on your box and set a domain name as follows:

```
git remote add piku piku@HOSTNAME:piku-monitoring
git push piku main
piku config:set NGINX_SERVER_NAME=...
```

By default it uses a local sqlite database but you can change that by setting the `DATABASE` variable:

```
DATABASE_URL=postgres://someuser:somepassword@somehost:5432/somedatabase
```
