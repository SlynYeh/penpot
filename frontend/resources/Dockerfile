# FROM penpotapp/frontend:latest 以官方镜像为基础镜像

# 以本地某个image为基础镜像
FROM penpotapp/frontend:test0818B

# RUN mv /var/www/app/ /tmp/     把移动官方镜像移动的临时目录，也可以直接删除
RUN rm -rf  /var/www/app/

ADD --chown=penpot:root ./public/ /var/www/app/
RUN date > /var/www/app/VERSION