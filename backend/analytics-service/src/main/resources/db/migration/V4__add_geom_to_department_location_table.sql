apiVersion: v1
items:
- apiVersion: apps/v1
  kind: Deployment
  metadata:
    labels:
      app: medical-agent
    name: medical-agent
    namespace: langgraph
  spec:
    progressDeadlineSeconds: 600
    replicas: 1
    revisionHistoryLimit: 10
    selector:
      matchLabels:
        app: medical-agent
    strategy:
      type: Recreate
    template:
      metadata:
        labels:
          app: medical-agent
      spec:
        containers:
        - image: biharkrishi.azurecr.io/biharkrishi/medical-tourism:dev
          imagePullPolicy: Always
          name: medical-agent
          ports:
          - containerPort: 3000
            protocol: TCP
          resources:
            limits:
              memory: 1000Mi
            requests:
              cpu: 100m
              memory: 128Mi
          terminationMessagePath: /dev/termination-log
          terminationMessagePolicy: File
        dnsPolicy: ClusterFirst
        imagePullSecrets:
        - name: biharkrishi
        restartPolicy: Always
        schedulerName: default-scheduler
        securityContext: {}
        terminationGracePeriodSeconds: 30
- apiVersion: v1
  kind: Service
  metadata:
    name: medical-agent
    namespace: langgraph
  spec:
    ports:
    - port: 3000
      protocol: TCP
      targetPort: 3000
    selector:
      app: medical-agent
    sessionAffinity: None
    type: ClusterIP
  status:
    loadBalancer: {}
- apiVersion: networking.k8s.io/v1
  kind: Ingress
  metadata:
    annotations:
      kubectl.kubernetes.io/last-applied-configuration: |
      kubernetes.io/ingress.class: nginx
      nginx.ingress.kubernetes.io/force-ssl-redirect: "false"
      nginx.ingress.kubernetes.io/ssl-redirect: "true"
    generation: 1
    name: medical-agent
    namespace: langgraph
  spec:
    rules:
    - host: talk2dfs.beehyv.com
      http:
        paths:
        - backend:
            service:
              name: medical-agent
              port:
                number: 3000
          path: /medical-agent(/|$)(.*)
          pathType: Prefix
    tls:
    - hosts:
      - talk2dfs.beehyv.com
      secretName: talk2dfs.beehyv.com-tls-certs
kind: List
metadata:
  resourceVersion: ""