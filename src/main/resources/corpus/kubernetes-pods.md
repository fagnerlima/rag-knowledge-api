# Kubernetes Pods

A Pod is the smallest unit Kubernetes schedules. It groups one or more containers that always run together on the same node, share a network namespace and can share storage volumes. Most Pods run a single application container; additional containers are used when processes are tightly coupled, such as a log shipper or a proxy next to the application.

## Shared network and storage

All containers in a Pod share one IP address and one port space, so they talk to each other over `localhost` and must not listen on the same port. Volumes declared in the Pod specification can be mounted by several of its containers, which is how a sidecar reads files the main container writes. Containers in different Pods communicate through the cluster network, normally through a Service that gives a stable name and address to a changing set of Pods.

## Pods are ephemeral

A Pod is not repaired or moved: when its node fails, or it is evicted, it is gone and a controller creates a replacement with a new name and a new IP address. That is why Pods are rarely created directly. A Deployment manages stateless replicas and rolling updates, a StatefulSet gives Pods stable identities and their own persistent volumes, a DaemonSet runs one Pod per node, and a Job runs Pods to completion. Application state that must survive a restart belongs in a persistent volume or an external service, never in the container file system.

## Lifecycle and restarts

A Pod moves through the phases Pending, Running, Succeeded, Failed and Unknown. Inside a running Pod, the kubelet restarts containers that exit, according to the restart policy (`Always`, `OnFailure` or `Never`), with an exponential back-off that appears as `CrashLoopBackOff` when a container keeps failing. On deletion, each container receives `SIGTERM`, then `SIGKILL` once the grace period (30 seconds by default) expires, so applications should stop accepting work and finish in-flight requests when they receive `SIGTERM`.

## Init containers and sidecars

Init containers run one after another before the application containers start and must each succeed; they are used for setup such as waiting for a dependency or preparing files. Sidecar containers, declared as init containers with `restartPolicy: Always`, start before the application and keep running alongside it for the whole life of the Pod.

## Probes

Three probes tell the kubelet about container health. A startup probe protects slow-starting applications: the other probes wait until it succeeds. A liveness probe restarts a container that is stuck. A readiness probe removes the Pod from Service endpoints while it cannot serve traffic, without restarting it. A frequent mistake is making the liveness probe depend on a database: a database outage then restarts every replica and makes recovery slower.

## Resources

Each container can declare resource requests and limits. The scheduler places Pods using requests; limits cap usage at run time. A container above its memory limit is killed (`OOMKilled`), while one above its CPU limit is throttled. Requests and limits together define the quality-of-service class (Guaranteed, Burstable or BestEffort), which decides which Pods are evicted first when a node runs out of memory.
