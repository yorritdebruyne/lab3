# System Y - Naming Server (Lab 3)
This project implements the **Naming Server** for system Y, a distributed file system organized in a ring topology:
> Naming server has a map to store couples of (Int, Ip)
> The Naming server must be able to add and remove nodes from the map.
> Suppose N is the collection of nodes with a hash smaller than the hash of the filename, then the node with the smallest difference between its hash and the file hash is the owner of the file. If N is empty, the node with the biggest hash stores the requested file.

## 1. Purpose
naming server:
- keeps track of all nodes in the system Y ring
- maps node names and filenames to integer IDs in `[0,32768]` using a hashing function
- determines which node is responsible for storing a given file
- exposes this functionality via a REST API for other nodes

## 2. Hashing
The Naming server maps Java's hashCode() range (`[-2,147,483,647 , 2,147,483,647]`) into the required system Y range,
as given in the lab slides.
The hashing logic is used for Node and File IDs, this ensures that all IDS fall within the system Y ring.

## 3. Ring Ownership Algorithm
When a node requests the location of a file, the naming server determines the owner using the ring topology described in the lab:
  if N is not empty then the owner of the file is the node with the largest ID in N
  if N is empty then the owner is the node with the largest ID overall
N are all the nodes where the nodeID is smaller than the fileHash.

## 4. REST API
The naming server exposes three endpoints
-add node:
  add nides to the ring structure, if the name of a node already exists then the server will reject the request
-remove node
-resolve file owner
  server returns 404 not found if no node exists
